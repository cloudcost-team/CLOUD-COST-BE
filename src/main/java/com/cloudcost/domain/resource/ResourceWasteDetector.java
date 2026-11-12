package com.cloudcost.domain.resource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 방치 리소스 규칙 (ADR-020). 모두 Describe 결과(+LB는 CloudWatch 요청 수)만으로 판단한다.
 * <ul>
 *   <li>UNATTACHED_VOLUME: 어떤 서버에도 연결되지 않은(available) 볼륨
 *   <li>UNUSED_EIP: 어떤 서버·네트워크 인터페이스에도 연결되지 않은 고정 IP
 *   <li>OLD_SNAPSHOT: 내 계정이 만든 지 90일이 넘은 스냅샷
 *   <li>IDLE_LB: 만든 지 14일이 넘었고 최근 14일 요청이 0인 로드밸런서
 *   <li>FORGOTTEN_REGION: 연결한 기본 리전이 아닌 리전에서 실행 중인 서버
 * </ul>
 */
public final class ResourceWasteDetector {

    public static final Duration OLD_SNAPSHOT_AGE = Duration.ofDays(90);
    public static final Duration IDLE_LB_WINDOW = Duration.ofDays(14);
    static final String REGION_PRICE_NOTE = "이 리전 단가는 us-east-1 기준으로 추정했어요.";
    static final String SNAPSHOT_PRICE_NOTE = "원본 볼륨 크기 기준 최대 추정이에요 (스냅샷은 바뀐 부분만 저장돼 실제로는 더 적을 수 있어요).";
    static final String LB_PRICE_NOTE = "시간당 기본 요금만 넣었어요 (사용량 요금 LCU 제외).";

    private ResourceWasteDetector() {}

    /**
     * @param homeRegion       연결한 기본 리전
     * @param instanceHourlyUsd 다른 리전 서버의 시간당 온디맨드 단가 (instanceId → USD). 모르면 빠진다
     */
    public static List<ResourceFinding> detect(
        List<AwsResource> resources, String homeRegion, Instant now, Map<String, BigDecimal> instanceHourlyUsd) {
        List<ResourceFinding> findings = new ArrayList<>();
        for (AwsResource r : resources) {
            switch (r.type()) {
                case VOLUME -> {
                    if (!r.inUse() && r.sizeGb() != null) {
                        findings.add(volume(r));
                    }
                }
                case ADDRESS -> {
                    if (!r.inUse()) {
                        findings.add(address(r));
                    }
                }
                case SNAPSHOT -> {
                    if (r.createdAt() != null && !r.createdAt().plus(OLD_SNAPSHOT_AGE).isAfter(now) && r.sizeGb() != null) {
                        findings.add(snapshot(r, now));
                    }
                }
                case LOAD_BALANCER -> {
                    if (r.requestCount() != null && r.requestCount() == 0
                        && r.createdAt() != null && !r.createdAt().plus(IDLE_LB_WINDOW).isAfter(now)) {
                        findings.add(loadBalancer(r));
                    }
                }
                case INSTANCE -> {
                    if (r.inUse() && homeRegion != null && !homeRegion.equals(r.region())) {
                        findings.add(forgotten(r, instanceHourlyUsd.get(r.resourceId())));
                    }
                }
            }
        }
        findings.sort(Comparator.comparing(
                (ResourceFinding f) -> f.monthlyCostUsd() == null ? BigDecimal.ZERO : f.monthlyCostUsd())
            .reversed()
            .thenComparing(f -> f.resource().region())
            .thenComparing(f -> f.resource().resourceId()));
        return List.copyOf(findings);
    }

    private static ResourceFinding volume(AwsResource r) {
        BigDecimal monthly = ResourcePrices.volumeMonthly(r.region(), r.subtype(), r.sizeGb());
        return new ResourceFinding(ResourceWasteKind.UNATTACHED_VOLUME, r, money(monthly),
            r.subtype() + " " + r.sizeGb() + "GiB · 어떤 서버에도 연결되어 있지 않아요",
            "필요 없다면 스냅샷을 남긴 뒤 AWS 콘솔에서 볼륨 삭제 검토",
            List.of("EC2 → Elastic Block Store → 볼륨에서 " + r.resourceId() + "을(를) 골라요 (리전 " + r.region() + ")",
                "필요하면 작업 → 스냅샷 생성으로 백업해요",
                "작업 → 볼륨 삭제를 눌러요"),
            List.of("중지한 서버에서 떼어 둔 볼륨일 수 있어요. 담당자에게 먼저 확인해 주세요.",
                "삭제한 볼륨은 되돌릴 수 없어요."),
            monthly == null ? "이 볼륨 종류(" + r.subtype() + ")의 단가를 찾지 못했어요." : regionNote(r.region()));
    }

    private static ResourceFinding address(AwsResource r) {
        return new ResourceFinding(ResourceWasteKind.UNUSED_EIP, r, money(ResourcePrices.addressMonthly()),
            "어떤 서버에도 연결되어 있지 않은 고정 IP예요 (연결하지 않아도 시간당 요금이 나가요)",
            "쓰지 않는다면 AWS 콘솔에서 고정 IP 릴리스 검토",
            List.of("EC2 → 네트워크 및 보안 → 탄력적 IP에서 " + r.resourceId() + "을(를) 골라요 (리전 " + r.region() + ")",
                "작업 → 탄력적 IP 주소 릴리스를 눌러요"),
            List.of("릴리스한 IP 주소는 다시 받을 수 없어요. 도메인(DNS)에 이 주소를 적어 두지 않았는지 확인해 주세요."),
            null);
    }

    private static ResourceFinding snapshot(AwsResource r, Instant now) {
        long days = Duration.between(r.createdAt(), now).toDays();
        return new ResourceFinding(ResourceWasteKind.OLD_SNAPSHOT, r,
            money(ResourcePrices.snapshotMonthly(r.region(), r.sizeGb())),
            "만든 지 " + days + "일 지난 스냅샷 (원본 " + r.sizeGb() + "GiB)",
            "더 이상 복구에 쓰지 않는다면 AWS 콘솔에서 스냅샷 삭제 검토",
            List.of("EC2 → Elastic Block Store → 스냅샷에서 " + r.resourceId() + "을(를) 골라요 (리전 " + r.region() + ")",
                "작업 → 스냅샷 삭제를 눌러요"),
            List.of("AMI(서버 이미지)가 쓰는 스냅샷은 지울 수 없어요. AMI를 먼저 확인해 주세요.",
                "백업 정책으로 남겨 두는 스냅샷인지 담당자에게 확인해 주세요."),
            SNAPSHOT_PRICE_NOTE);
    }

    private static ResourceFinding loadBalancer(AwsResource r) {
        return new ResourceFinding(ResourceWasteKind.IDLE_LB, r, money(ResourcePrices.loadBalancerMonthly(r.region())),
            "최근 14일 동안 요청이 한 건도 없어요 (" + r.subtype() + ")",
            "쓰지 않는다면 AWS 콘솔에서 로드밸런서 삭제 검토",
            List.of("EC2 → 로드 밸런싱 → 로드 밸런서에서 " + r.resourceId() + "을(를) 골라요 (리전 " + r.region() + ")",
                "작업 → 로드 밸런서 삭제를 눌러요"),
            List.of("판단 근거는 CloudWatch 기본 지표(요청 수)뿐이에요. 장애 대비용으로 둔 것인지 확인해 주세요."),
            LB_PRICE_NOTE);
    }

    private static ResourceFinding forgotten(AwsResource r, BigDecimal hourlyUsd) {
        BigDecimal monthly = hourlyUsd == null ? null : hourlyUsd.multiply(ResourcePrices.HOURS_PER_MONTH);
        return new ResourceFinding(ResourceWasteKind.FORGOTTEN_REGION, r, money(monthly),
            "연결한 기본 리전이 아닌 " + r.region() + "에서 " + r.subtype() + " 서버가 실행 중이에요",
            "쓰지 않는 서버라면 AWS 콘솔에서 중지 검토",
            List.of("AWS 콘솔 오른쪽 위에서 리전을 " + r.region() + "(으)로 바꿔요",
                "EC2 → 인스턴스에서 " + r.resourceId() + "을(를) 골라요",
                "인스턴스 상태 → 인스턴스 중지를 눌러요"),
            List.of("다른 리전에서 일부러 운영하는 서버일 수 있어요. 담당자에게 먼저 확인해 주세요.",
                "월 추정 비용은 한 달 내내 켜 둔다고 가정한 값이에요 (사용 시간은 기본 리전만 수집해요)."),
            hourlyUsd == null ? "이 서버의 단가를 찾지 못했어요." : null);
    }

    private static String regionNote(String region) {
        return ResourcePrices.regionKnown(region) ? null : REGION_PRICE_NOTE;
    }

    private static BigDecimal money(BigDecimal usd) {
        return usd == null ? null : usd.setScale(2, RoundingMode.HALF_UP);
    }
}
