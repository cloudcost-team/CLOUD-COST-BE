package com.cloudcost.domain.simulation;

import com.cloudcost.domain.DomainValidationException;
import com.cloudcost.domain.waste.WasteFinding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 비용 시뮬레이터 (이슈 #31, S08). 저장하지 않는 계산이다. 금액은 USD다.
 *
 * <ul>
 *   <li>적용 전 = 월말 예상 × (1 + 트래픽 증가율)
 *   <li>적용 후 = (월말 예상 − 고른 추천의 월 절감액 합) × (1 + 트래픽 증가율). 0보다 작아지지 않는다
 *   <li>월 절감 = 적용 전 − 적용 후, 연 절감 = 월 절감 × 12
 *   <li>예산이 있으면 적용 후가 예산 이하인지(withinBudget)
 * </ul>
 * 트래픽이 늘면 서버 사용량도 같은 비율로 는다고 단순하게 가정한다.
 *
 * @param selected        고른 추천 (요청 순서, 중복 제거)
 * @param budgetUsd       비교용 예산. 없으면 null
 * @param withinBudget    적용 후 ≤ 예산. 예산이 없으면 null
 * @param beforeBudgetRate 적용 전 ÷ 예산 (%). 예산이 없으면 null
 * @param afterBudgetRate  적용 후 ÷ 예산 (%). 예산이 없으면 null
 */
public record CostSimulation(
    BigDecimal monthEndUsd,
    int trafficGrowthPercent,
    List<WasteFinding> selected,
    BigDecimal selectedSavingsUsd,
    BigDecimal beforeUsd,
    BigDecimal afterUsd,
    BigDecimal monthlySavingsUsd,
    BigDecimal yearlySavingsUsd,
    BigDecimal budgetUsd,
    Boolean withinBudget,
    BigDecimal beforeBudgetRate,
    BigDecimal afterBudgetRate) {

    public static final int MAX_GROWTH_PERCENT = 100;
    public static final String UNKNOWN = "UNKNOWN_RECOMMENDATION";
    public static final String METHOD =
        "적용 후 = (월말 예상 − 고른 추천의 월 절감액) × (1 + 트래픽 증가율). 트래픽이 늘면 서버 사용량도 같은 비율로 는다고 가정했어요.";

    /**
     * 요청한 추천 id를 지금 추천 목록에서 찾는다. 중복은 한 번만 센다.
     *
     * @throws DomainValidationException 목록에 없는 id가 있으면 {@link #UNKNOWN}
     */
    public static List<WasteFinding> select(List<WasteFinding> findings, List<String> recommendationIds) {
        Map<String, WasteFinding> byId = new LinkedHashMap<>();
        findings.forEach(f -> byId.put(f.recommendationId(), f));
        Set<String> ids = new LinkedHashSet<>(recommendationIds == null ? List.of() : recommendationIds);
        List<String> unknown = new ArrayList<>();
        List<WasteFinding> selected = new ArrayList<>();
        for (String id : ids) {
            WasteFinding f = byId.get(id);
            if (f == null) {
                unknown.add(id);
            } else {
                selected.add(f);
            }
        }
        if (!unknown.isEmpty()) {
            throw new DomainValidationException(UNKNOWN,
                "지금 추천 목록에 없는 추천이에요: " + String.join(", ", unknown) + ". 낭비 리소스 목록을 다시 불러와 주세요.");
        }
        return List.copyOf(selected);
    }

    public static void requireValidGrowth(int trafficGrowthPercent) {
        if (trafficGrowthPercent < 0 || trafficGrowthPercent > MAX_GROWTH_PERCENT) {
            throw new DomainValidationException("VALIDATION_ERROR",
                "트래픽 증가율은 0~" + MAX_GROWTH_PERCENT + "% 사이로 입력해 주세요.");
        }
    }

    /**
     * @param monthEndUsd 월말 예상 (USD)
     * @param budgetUsd   비교용 예산. 없으면 null
     */
    public static CostSimulation of(
        BigDecimal monthEndUsd, List<WasteFinding> selected, int trafficGrowthPercent, BigDecimal budgetUsd) {
        requireValidGrowth(trafficGrowthPercent);
        BigDecimal growth = BigDecimal.ONE.add(
            BigDecimal.valueOf(trafficGrowthPercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY));
        BigDecimal savings = selected.stream().map(WasteFinding::monthlySavingsUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal before = monthEndUsd.multiply(growth);
        BigDecimal after = monthEndUsd.subtract(savings).max(BigDecimal.ZERO).multiply(growth);
        BigDecimal monthly = before.subtract(after);
        boolean hasBudget = budgetUsd != null && budgetUsd.signum() > 0;
        return new CostSimulation(monthEndUsd, trafficGrowthPercent, List.copyOf(selected), savings,
            scale(before), scale(after), scale(monthly), scale(monthly.multiply(BigDecimal.valueOf(12))),
            budgetUsd, hasBudget ? after.compareTo(budgetUsd) <= 0 : null,
            hasBudget ? rate(before, budgetUsd) : null, hasBudget ? rate(after, budgetUsd) : null);
    }

    private static BigDecimal rate(BigDecimal amount, BigDecimal budgetUsd) {
        return amount.multiply(BigDecimal.valueOf(100)).divide(budgetUsd, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal usd) {
        return usd.setScale(6, RoundingMode.HALF_UP);
    }
}
