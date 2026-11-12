package com.cloudcost.application;

import com.cloudcost.application.port.AccountResourceRepository;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.account.CustomerRolePolicy;
import com.cloudcost.domain.cost.InstancePrice;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.resource.ResourceFinding;
import com.cloudcost.domain.resource.ResourceScan;
import com.cloudcost.domain.resource.ResourceType;
import com.cloudcost.domain.resource.ResourceWasteDetector;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 방치 리소스·잊힌 리전 (ADR-020). 확장 수집 결과를 규칙으로 판정하고, 권한이 부족하면 오류 대신 스택 업데이트 방법을 준다.
 */
public class ResourceWasteService {

    /**
     * @param otherRegionServers 기본 리전 밖에서 실행 중인 서버 수 (리전 → 수, 리전 이름 순)
     * @param totalMonthlyUsd    발견 항목을 모두 정리했을 때의 월 추정 금액. 단가를 모르는 항목이 하나라도 있으면 null (부분합을 전체처럼 보이지 않게)
     * @param upgrade            권한 업데이트가 필요하면 방법, 아니면 null
     * @param refreshing         백그라운드에서 다시 훑는 중이면 true (이번 응답은 저장된 결과)
     */
    public record ResourceWasteView(
        UUID accountId,
        String homeRegion,
        ResourceScan scan,
        List<ResourceFinding> findings,
        BigDecimal totalMonthlyUsd,
        Map<String, Integer> otherRegionServers,
        Upgrade upgrade,
        boolean refreshing) {
    }

    /**
     * 권한을 늘리는 방법.
     *
     * @param method           STACK = 빠른 생성 스택을 새 템플릿으로 업데이트, MANUAL_ROLE = 직접 만든 Role에 정책을 고쳐 붙인다
     * @param stackName        STACK일 때만
     * @param templateUrl      STACK일 때만
     * @param permissionPolicy MANUAL_ROLE일 때 Role에 붙일 권한 정책 (STACK이면 null)
     */
    public record Upgrade(
        String method, String stackName, String consoleUrl, String templateUrl, Map<String, Object> permissionPolicy,
        List<String> steps) {
    }

    private final AwsAccountService accounts;
    private final ResourceScanService scans;
    private final AccountResourceRepository resources;
    private final PriceService prices;
    private final Clock clock;
    private final String templateUrl;

    public ResourceWasteService(
        AwsAccountService accounts, ResourceScanService scans, AccountResourceRepository resources, PriceService prices,
        Clock clock, String templateUrl) {
        this.accounts = accounts;
        this.scans = scans;
        this.resources = resources;
        this.prices = prices;
        this.clock = clock;
        this.templateUrl = templateUrl;
    }

    public ResourceWasteView view(String ownerId, UUID accountId) {
        AwsAccount account = accounts.get(ownerId, accountId);
        boolean refreshing = account.canCollect() && scans.requestRefresh(ownerId, accountId);
        ResourceScan scan = scans.current(ownerId, accountId);
        List<AwsResource> list = resources.findAllByOwnerIdAndAccountId(ownerId, accountId);
        return build(account, scan, list, clock.instant(), refreshing);
    }

    ResourceWasteView build(AwsAccount account, ResourceScan scan, List<AwsResource> list, Instant now, boolean refreshing) {
        String home = account.region().value();
        List<Ec2Instance> remote = list.stream()
            .filter(r -> r.type() == ResourceType.INSTANCE && r.inUse() && !home.equals(r.region()))
            .map(ResourceWasteService::asInstance)
            .toList();
        Map<String, BigDecimal> hourly = new HashMap<>();
        if (!remote.isEmpty()) {
            for (Map.Entry<String, InstancePrice> e : prices.pricesFor(remote).byInstanceId().entrySet()) {
                if (e.getValue().hourlyUsd() != null) {
                    hourly.put(e.getKey(), e.getValue().hourlyUsd());
                }
            }
        }
        List<ResourceFinding> findings = ResourceWasteDetector.detect(list, home, now, hourly);
        BigDecimal total = total(findings);
        Map<String, Integer> others = new TreeMap<>();
        remote.forEach(i -> others.merge(i.region(), 1, Integer::sum));
        Upgrade upgrade = scan.status() == ResourceScan.Status.PERMISSION_UPGRADE_REQUIRED ? upgrade(account) : null;
        return new ResourceWasteView(account.id(), home, scan, findings, total, others, upgrade, refreshing);
    }

    /** 체험 모드 예시 (ADR-008): 실제 AWS를 부르지 않는다 */
    public static ResourceWasteView demo(Instant now) {
        List<AwsResource> list = com.cloudcost.domain.demo.DemoResources.resources(now);
        String home = com.cloudcost.domain.demo.DemoResources.HOME_REGION;
        List<ResourceFinding> findings = ResourceWasteDetector.detect(
            list, home, now, com.cloudcost.domain.demo.DemoResources.HOURLY_USD);
        BigDecimal total = total(findings);
        Map<String, Integer> others = new TreeMap<>();
        list.stream().filter(r -> r.type() == ResourceType.INSTANCE && r.inUse() && !home.equals(r.region()))
            .forEach(r -> others.merge(r.region(), 1, Integer::sum));
        return new ResourceWasteView(null, home,
            new ResourceScan(ResourceScan.Status.OK, ResourceScan.CURRENT_PERMISSION_VERSION, now, null),
            findings, total, others, null, false);
    }

    /** 단가를 모르는 항목이 하나라도 있으면 null. 발견 항목이 없으면 0 */
    static BigDecimal total(List<ResourceFinding> findings) {
        if (findings.stream().map(ResourceFinding::monthlyCostUsd).anyMatch(Objects::isNull)) {
            return null;
        }
        return findings.stream().map(ResourceFinding::monthlyCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static final String DENY_POLICY_STEP =
        "그래도 같은 안내가 나오면 조직 정책(SCP)·권한 경계·거부(Deny) 정책이 이 권한을 막고 있는지 확인해 주세요.";

    private Upgrade upgrade(AwsAccount account) {
        String[] stack = stackRegionAndName(account.stackId());
        if (stack == null) {
            return manualUpgrade(account);
        }
        String region = stack[0];
        String name = stack[1];
        String console = "https://console.aws.amazon.com/cloudformation/home?region=" + region
            + "#/stacks?filteringText=" + name;
        return new Upgrade("STACK", name, console, templateUrl, null, List.of(
            "AWS 콘솔 → CloudFormation에서 '" + name + "' 스택을 골라요 (리전 " + region + ")",
            "업데이트 → 기존 템플릿 대체 → Amazon S3 URL에 아래 템플릿 주소를 붙여 넣어요",
            "파라미터는 그대로 두고 다음을 눌러, IAM 리소스 생성 확인에 체크한 뒤 제출해요",
            "1시간 안에 자동으로 다시 확인해요. 추가되는 권한은 모두 읽기(Describe)예요.",
            DENY_POLICY_STEP));
    }

    /** 스택 없이 직접 만든 Role (PUT .../role로 등록한 연결): Role 권한 정책을 직접 바꾼다 */
    private static Upgrade manualUpgrade(AwsAccount account) {
        String role = account.roleArn() == null ? null : account.roleArn().roleName();
        String console = role == null
            ? "https://console.aws.amazon.com/iam/home#/roles"
            : "https://console.aws.amazon.com/iam/home#/roles/details/" + role;
        return new Upgrade("MANUAL_ROLE", null, console, null, CustomerRolePolicy.permissionPolicy(), List.of(
            "AWS 콘솔 → IAM → 역할에서 " + (role == null ? "연결한 Role" : "'" + role + "'") + "을(를) 골라요",
            "권한 탭에서 CloudCost용 인라인 정책을 편집하고, JSON을 아래 permissionPolicy로 바꿔 저장해요",
            "1시간 안에 자동으로 다시 확인해요. 추가되는 권한은 모두 읽기(Describe)예요.",
            DENY_POLICY_STEP));
    }

    /** arn:aws:cloudformation:{region}:{account}:stack/{name}/{id} → {region, name}. 형식이 다르면 null */
    static String[] stackRegionAndName(String stackId) {
        if (stackId == null) {
            return null;
        }
        String[] parts = stackId.split(":", 6);
        if (parts.length < 6 || !"cloudformation".equals(parts[2]) || parts[3].isBlank()
            || !parts[5].startsWith("stack/")) {
            return null;
        }
        String[] path = parts[5].split("/");
        if (path.length < 2 || path[1].isBlank()) {
            return null;
        }
        return new String[] {parts[3], path[1]};
    }

    /** 다른 리전 서버의 단가 조회용. 테넌시·스팟 정보는 저장하지 않으므로 공유·온디맨드로 추정한다 */
    private static Ec2Instance asInstance(AwsResource r) {
        return new Ec2Instance(r.resourceId(), r.name(), r.subtype(), r.region(), null, "running", r.createdAt(),
            r.platform(), "default", false, false);
    }
}
