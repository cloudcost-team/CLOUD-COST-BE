package com.cloudcost.domain.benchmark;

import com.cloudcost.domain.benchmark.BenchmarkReference.UsagePercentile;
import com.cloudcost.domain.benchmark.TeamBenchmark.Position;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.InstanceSpecs;
import com.cloudcost.domain.usage.UtilizationStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 팀 벤치마킹 ③ 규모 대비 월 지출 (ADR-013). 금액은 USD다.
 *
 * <p>비교 자료(Azure Public Dataset V2)에는 가격이 없고 코어 수도 구간뿐이라, 금액이 아니라 <b>한 달 사용량
 * (vCPU-시간 = vCPU × 켜진 시간)</b>으로 순위를 매긴다. 같은 규모 팀의 사용량 분포는 서버 대수로 예측하는 분위 회귀 모델
 * ({@link BenchmarkReference.SpendModel})이 준다. 우리 팀도 같은 코어 구간 규칙
 * ({@link #vcpuBucket})으로 재므로 단가 가정이 순위에 영향을 주지 않는다. 화면의 금액 범위는 비교 팀의 25~75번째 백분위
 * 사용량을 <b>우리 팀의 vCPU-시간당 비용</b>(월말 예상 ÷ 우리 사용량)으로 환산한 값이다.
 */
public final class SpendBenchmark {

    /** 사용량을 재는 기간 (낭비 탐지·과다 사양 비율과 같은 최근 14일) */
    static final BigDecimal WINDOW_HOURS = BigDecimal.valueOf(UtilizationStats.WINDOW.toHours());
    /** 비교 자료의 기록 기간 (30일)으로 맞춘다 */
    static final BigDecimal MONTH_HOURS = BigDecimal.valueOf(30 * 24);
    /** 일반 범위로 보여 주는 백분위 (가운데 절반) */
    public static final BigDecimal LOW_PERCENTILE = BigDecimal.valueOf(25);
    public static final BigDecimal HIGH_PERCENTILE = BigDecimal.valueOf(75);
    public static final BigDecimal MEDIAN_PERCENTILE = BigDecimal.valueOf(50);

    private SpendBenchmark() {
    }

    /**
     * @param servers      사용량을 센 서버 수 (최근 14일에 1시간 이상 켜졌고 vCPU를 아는 서버)
     * @param vcpuHours    30일로 환산한 한 달 사용량
     * @param unknownTypes vCPU를 몰라 뺀 인스턴스 타입
     */
    public record Usage(int servers, BigDecimal vcpuHours, List<String> unknownTypes) {
    }

    /** 금액 범위 (USD) */
    public record UsdRange(BigDecimal minUsd, BigDecimal maxUsd) {
    }

    /**
     * 비교 자료의 코어 구간(2·4·8·24·24 초과)에 맞춘 vCPU. 원본 계산 스크립트(scripts/benchmark/AzureSpend.java)와 같은 규칙이어야 한다.
     */
    public static int vcpuBucket(int vcpu) {
        if (vcpu <= 2) {
            return 2;
        }
        if (vcpu <= 4) {
            return 4;
        }
        if (vcpu <= 8) {
            return 8;
        }
        return vcpu <= 24 ? 24 : 32;
    }

    /**
     * 우리 팀의 한 달 사용량 = Σ 코어 구간 vCPU × 최근 14일 켜진 시간(CPU 기록이 있는 시간) × 30일 ÷ 14일.
     *
     * @param stats instanceId → 최근 14일 사용률 ({@link UtilizationStats#cpuHours()}가 켜진 시간)
     */
    public static Usage monthlyUsage(List<Ec2Instance> instances, Map<String, UtilizationStats> stats) {
        int servers = 0;
        BigDecimal sum = BigDecimal.ZERO;
        TreeSet<String> unknown = new TreeSet<>();
        for (Ec2Instance i : instances) {
            UtilizationStats s = stats.get(i.instanceId());
            if (s == null || s.cpuHours() <= 0) {
                continue;
            }
            Optional<InstanceSpecs.Spec> spec = InstanceSpecs.find(i.instanceType());
            if (spec.isEmpty()) {
                unknown.add(i.instanceType());
                continue;
            }
            servers++;
            sum = sum.add(BigDecimal.valueOf((long) vcpuBucket(spec.get().vcpu()) * s.cpuHours()));
        }
        BigDecimal monthly = sum.multiply(MONTH_HOURS).divide(WINDOW_HOURS, 1, RoundingMode.HALF_UP);
        return new Usage(servers, monthly, List.copyOf(unknown));
    }

    /**
     * 우리보다 사용량이 확실히 적은 팀의 비율 (%). 표에서 사용량이 우리보다 적은 가장 큰 백분위다 (5% 단위 하한).
     * 같은 사용량인 팀(예: 2vCPU 1대를 한 달 내내)은 넣지 않는다. 표가 비면 null.
     */
    public static BigDecimal teamsUsingLessPercent(BigDecimal vcpuHours, List<UsagePercentile> table) {
        if (vcpuHours == null || table.isEmpty()) {
            return null;
        }
        BigDecimal best = BigDecimal.ZERO;
        for (UsagePercentile p : table) {
            if (p.vcpuHours().compareTo(vcpuHours) < 0 && p.percentile().compareTo(best) > 0) {
                best = p.percentile();
            }
        }
        return best.setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 같은 규모 팀의 일반 범위(25~75번째 백분위 사용량)를 우리 팀의 vCPU-시간당 비용으로 환산한 금액.
     * 우리 사용량이 0이면 환산할 수 없어 empty.
     */
    public static Optional<UsdRange> typicalRange(
        List<UsagePercentile> table, BigDecimal monthEndUsd, BigDecimal ourVcpuHours) {
        if (ourVcpuHours == null || ourVcpuHours.signum() <= 0 || monthEndUsd == null) {
            return Optional.empty();
        }
        BigDecimal low = at(table, LOW_PERCENTILE);
        BigDecimal high = at(table, HIGH_PERCENTILE);
        return Optional.of(new UsdRange(
            low.multiply(monthEndUsd).divide(ourVcpuHours, 6, RoundingMode.HALF_UP),
            high.multiply(monthEndUsd).divide(ourVcpuHours, 6, RoundingMode.HALF_UP)));
    }

    /** 표에서 정확히 그 백분위의 사용량 (파일을 읽을 때 25·75가 있는지 확인한다) */
    public static BigDecimal at(List<UsagePercentile> table, BigDecimal percentile) {
        return table.stream().filter(p -> p.percentile().compareTo(percentile) == 0).findFirst()
            .map(UsagePercentile::vcpuHours)
            .orElseThrow(() -> new IllegalStateException("사용량 표에 " + percentile + "번째 백분위가 없습니다"));
    }

    /** 범위 막대 위의 위치 (%). 하한 = 0, 상한 = 100이고 범위 밖이면 0 또는 100으로 맞춘다 (BELOW·ABOVE는 position으로 구분) */
    public static BigDecimal barPositionPercent(BigDecimal monthlyUsd, UsdRange range) {
        BigDecimal width = range.maxUsd().subtract(range.minUsd());
        if (width.signum() <= 0) {
            return monthlyUsd.compareTo(range.minUsd()) < 0 ? BigDecimal.ZERO.setScale(1) : BigDecimal.valueOf(100).setScale(1);
        }
        BigDecimal raw = monthlyUsd.subtract(range.minUsd()).multiply(BigDecimal.valueOf(100))
            .divide(width, 1, RoundingMode.HALF_UP);
        return raw.max(BigDecimal.ZERO.setScale(1)).min(BigDecimal.valueOf(100).setScale(1));
    }

    /** 월 지출이 범위 아래(min 미만)·안(min~max)·위(max 초과) */
    public static Position position(BigDecimal monthlyUsd, UsdRange range) {
        if (monthlyUsd.compareTo(range.minUsd()) < 0) {
            return Position.BELOW;
        }
        return monthlyUsd.compareTo(range.maxUsd()) > 0 ? Position.ABOVE : Position.WITHIN;
    }
}
