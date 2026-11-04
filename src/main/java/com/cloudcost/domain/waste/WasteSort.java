package com.cloudcost.domain.waste;

import java.util.Comparator;
import java.util.List;

/**
 * 낭비 리소스 목록 정렬 (기능명세서 F05-5). 같으면 절감액 큰 순 → 이름순.
 */
public enum WasteSort {
    /** 절감액 큰 순 (기본) */
    SAVINGS,
    /** 지금 비용 큰 순 (월 추정 비용) */
    COST,
    /** 사용률 낮은 순 (최근 14일 평균 CPU) */
    UTILIZATION,
    /** 이름순 */
    NAME;

    private static final Comparator<WasteFinding> TIE_BREAK =
        Comparator.comparing(WasteFinding::monthlySavingsUsd).reversed()
            .thenComparing(WasteFinding::displayName)
            .thenComparing(f -> f.instance().instanceId());

    public Comparator<WasteFinding> comparator() {
        return switch (this) {
            case SAVINGS -> TIE_BREAK;
            case COST -> Comparator.comparing(WasteFinding::monthlyCostUsd).reversed().thenComparing(TIE_BREAK);
            case UTILIZATION -> Comparator.comparing(
                (WasteFinding f) -> f.stats().cpuAvg(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TIE_BREAK);
            case NAME -> Comparator.comparing(WasteFinding::displayName).thenComparing(TIE_BREAK);
        };
    }

    /**
     * @param kind null이면 모두
     */
    public List<WasteFinding> apply(List<WasteFinding> findings, WasteKind kind) {
        return findings.stream()
            .filter(f -> kind == null || f.kind() == kind)
            .sorted(comparator())
            .toList();
    }
}
