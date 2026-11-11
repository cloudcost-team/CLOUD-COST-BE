package com.cloudcost.application;

import com.cloudcost.application.port.AccountResourceRepository;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.account.QuickCreateLink;
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
     * @param upgrade            권한 업데이트가 필요하면 방법, 아니면 null
     */
    public record ResourceWasteView(
        UUID accountId,
        String homeRegion,
        ResourceScan scan,
        List<ResourceFinding> findings,
        BigDecimal totalMonthlyUsd,
        Map<String, Integer> otherRegionServers,
        Upgrade upgrade) {
    }

    /** 이전 템플릿(v1)으로 만든 스택을 v2로 바꾸는 방법 */
    public record Upgrade(String stackName, String consoleUrl, String templateUrl, List<String> steps) {
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
        ResourceScan scan = scans.refreshIfStale(ownerId, accountId);
        List<AwsResource> list = resources.findAllByOwnerIdAndAccountId(ownerId, accountId);
        return build(account, scan, list, clock.instant());
    }

    ResourceWasteView build(AwsAccount account, ResourceScan scan, List<AwsResource> list, Instant now) {
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
        BigDecimal total = findings.stream().map(ResourceFinding::monthlyCostUsd).filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Integer> others = new TreeMap<>();
        remote.forEach(i -> others.merge(i.region(), 1, Integer::sum));
        Upgrade upgrade = scan.status() == ResourceScan.Status.PERMISSION_UPGRADE_REQUIRED ? upgrade(account) : null;
        return new ResourceWasteView(account.id(), home, scan, findings, total, others, upgrade);
    }

    /** 체험 모드 예시 (ADR-008): 실제 AWS를 부르지 않는다 */
    public static ResourceWasteView demo(Instant now) {
        List<AwsResource> list = com.cloudcost.domain.demo.DemoResources.resources(now);
        String home = com.cloudcost.domain.demo.DemoResources.HOME_REGION;
        List<ResourceFinding> findings = ResourceWasteDetector.detect(
            list, home, now, com.cloudcost.domain.demo.DemoResources.HOURLY_USD);
        BigDecimal total = findings.stream().map(ResourceFinding::monthlyCostUsd).filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Integer> others = new TreeMap<>();
        list.stream().filter(r -> r.type() == ResourceType.INSTANCE && r.inUse() && !home.equals(r.region()))
            .forEach(r -> others.merge(r.region(), 1, Integer::sum));
        return new ResourceWasteView(null, home,
            new ResourceScan(ResourceScan.Status.OK, ResourceScan.CURRENT_PERMISSION_VERSION, now, null),
            findings, total, others, null);
    }

    private Upgrade upgrade(AwsAccount account) {
        String stack = QuickCreateLink.stackName(account);
        String region = account.region().value();
        String console = "https://console.aws.amazon.com/cloudformation/home?region=" + region
            + "#/stacks?filteringText=" + stack;
        return new Upgrade(stack, console, templateUrl, List.of(
            "AWS 콘솔 → CloudFormation에서 '" + stack + "' 스택을 골라요 (리전 " + region + ")",
            "업데이트 → 기존 템플릿 대체 → Amazon S3 URL에 아래 템플릿 주소를 붙여 넣어요",
            "파라미터는 그대로 두고 다음을 눌러, IAM 리소스 생성 확인에 체크한 뒤 제출해요",
            "1시간 안에 자동으로 다시 확인해요. 추가되는 권한은 모두 읽기(Describe)예요."));
    }

    /** 다른 리전 서버의 단가 조회용. 테넌시·스팟 정보는 저장하지 않으므로 공유·온디맨드로 추정한다 */
    private static Ec2Instance asInstance(AwsResource r) {
        return new Ec2Instance(r.resourceId(), r.name(), r.subtype(), r.region(), null, "running", r.createdAt(),
            r.platform(), "default", false, false);
    }
}
