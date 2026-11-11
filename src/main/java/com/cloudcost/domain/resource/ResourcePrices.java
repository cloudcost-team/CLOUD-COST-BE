package com.cloudcost.domain.resource;

import java.math.BigDecimal;
import java.util.Map;

/**
 * EBS·고정 IP·스냅샷·로드밸런서 온디맨드 정가 (USD, 2026-11 기준 공개 가격표에서 옮긴 값 — ADR-020).
 * 서울(ap-northeast-2)과 버지니아(us-east-1)만 따로 두고, 다른 리전은 버지니아 값을 쓰며 응답에 그렇게 적는다.
 * 실제 청구와 다를 수 있는 추정치다 (LB 사용량 요금(LCU)·스냅샷 증분 저장은 넣지 않는다).
 */
public final class ResourcePrices {

    public static final String SEOUL = "ap-northeast-2";
    public static final String VIRGINIA = "us-east-1";
    public static final BigDecimal HOURS_PER_MONTH = new BigDecimal("730");

    /** GB-월 단가 */
    private static final Map<String, Map<String, BigDecimal>> VOLUME_GB_MONTH = Map.of(
        SEOUL, Map.of(
            "gp3", new BigDecimal("0.0912"), "gp2", new BigDecimal("0.114"), "io1", new BigDecimal("0.1278"),
            "io2", new BigDecimal("0.1278"), "st1", new BigDecimal("0.051"), "sc1", new BigDecimal("0.0174"),
            "standard", new BigDecimal("0.08")),
        VIRGINIA, Map.of(
            "gp3", new BigDecimal("0.08"), "gp2", new BigDecimal("0.10"), "io1", new BigDecimal("0.125"),
            "io2", new BigDecimal("0.125"), "st1", new BigDecimal("0.045"), "sc1", new BigDecimal("0.015"),
            "standard", new BigDecimal("0.05")));

    private static final Map<String, BigDecimal> SNAPSHOT_GB_MONTH =
        Map.of(SEOUL, new BigDecimal("0.05"), VIRGINIA, new BigDecimal("0.05"));
    /** 퍼블릭 IPv4 (연결 안 된 고정 IP 포함) 시간당 */
    private static final BigDecimal PUBLIC_IPV4_HOUR = new BigDecimal("0.005");
    /** ALB·NLB 고정 시간당 요금 (LCU 제외) */
    private static final Map<String, BigDecimal> LB_HOUR =
        Map.of(SEOUL, new BigDecimal("0.0225"), VIRGINIA, new BigDecimal("0.0225"));

    private ResourcePrices() {}

    /** 그 리전 단가를 따로 두었는지 (없으면 버지니아 값으로 추정) */
    public static boolean regionKnown(String region) {
        return SEOUL.equals(region) || VIRGINIA.equals(region);
    }

    /** @return 볼륨 타입을 모르면 null */
    public static BigDecimal volumeMonthly(String region, String volumeType, int sizeGb) {
        BigDecimal perGb = table(VOLUME_GB_MONTH, region).get(volumeType == null ? "" : volumeType);
        return perGb == null ? null : perGb.multiply(BigDecimal.valueOf(sizeGb));
    }

    public static BigDecimal snapshotMonthly(String region, int sizeGb) {
        return SNAPSHOT_GB_MONTH.getOrDefault(region, SNAPSHOT_GB_MONTH.get(VIRGINIA)).multiply(BigDecimal.valueOf(sizeGb));
    }

    public static BigDecimal addressMonthly() {
        return PUBLIC_IPV4_HOUR.multiply(HOURS_PER_MONTH);
    }

    public static BigDecimal loadBalancerMonthly(String region) {
        return LB_HOUR.getOrDefault(region, LB_HOUR.get(VIRGINIA)).multiply(HOURS_PER_MONTH);
    }

    private static <T> T table(Map<String, T> byRegion, String region) {
        return byRegion.getOrDefault(region, byRegion.get(VIRGINIA));
    }
}
