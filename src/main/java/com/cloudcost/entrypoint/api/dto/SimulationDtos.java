package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.CurrencyService.Resolved;
import com.cloudcost.application.SimulationService.SimulationView;
import com.cloudcost.domain.budget.MonthlyBudget;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.domain.simulation.CostSimulation;
import com.cloudcost.domain.waste.WasteFinding;
import com.cloudcost.domain.waste.WasteKind;
import com.cloudcost.entrypoint.api.dto.UsageDtos.ExchangeRateDto;
import com.cloudcost.entrypoint.api.dto.UsageDtos.FreshnessDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 비용 시뮬레이터 (S08 · S08-2). 금액은 요청한 통화로 환산한 추정치다. 프론트는 계산하지 않는다.
 */
public final class SimulationDtos {

    private SimulationDtos() {}

    public static final int MAX_IDS = 200;

    @Schema(description = "적용할 추천과 트래픽 증가율. 저장하지 않는다")
    public record SimulationRequest(
            @Schema(description = "낭비 리소스 응답의 recommendationId. 비우면 추천을 적용하지 않은 경우만 계산한다",
                    example = "[\"i-0abc:OVERSIZED\", \"i-0def:IDLE\"]")
                    @Size(max = MAX_IDS) List<String> recommendationIds,
            @Schema(description = "트래픽 증가율 (%) 0~100. 없으면 0", example = "20")
                    @Min(value = 0, message = "0~100 사이로 입력해 주세요")
                    @Max(value = CostSimulation.MAX_GROWTH_PERCENT, message = "0~100 사이로 입력해 주세요")
                    Integer trafficGrowthPercent) {

        public List<String> ids() {
            return recommendationIds == null ? List.of() : recommendationIds;
        }

        public int growth() {
            return trafficGrowthPercent == null ? 0 : trafficGrowthPercent;
        }
    }

    public record SelectedDto(
            String recommendationId,
            String instanceId,
            String name,
            WasteKind kind,
            @Schema(example = "과다 사양") String kindLabel,
            @Schema(example = "m5.large → t3.medium 으로 변경 검토") String action,
            @Schema(description = "매달 절감 예상 (currency 기준)", example = "65700") BigDecimal monthlySavings) {

        static SelectedDto of(WasteFinding f, DisplayCurrency d) {
            return new SelectedDto(f.recommendationId(), f.instance().instanceId(), f.displayName(), f.kind(),
                    f.kind().label(), f.action(), d.cost(f.monthlySavingsUsd()));
        }
    }

    public record CostDto(
            @Schema(description = "월 비용 (currency 기준)", example = "374400") BigDecimal monthlyCost,
            @Schema(description = "예산 대비 (%). 예산이 없으면 null", example = "93.6") BigDecimal budgetRate) {}

    @Schema(description = "월 예산. 정하지 않았으면 null")
    public record BudgetDto(
            @Schema(description = "예산 (currency 기준). 원화 예산인데 환율이 없으면 null") BigDecimal amount,
            BigDecimal enteredAmount,
            String enteredCurrency) {}

    public record SimulationResponse(
            String dataSource,
            String costBasis,
            @Schema(description = "금액 통화: KRW(기본) 또는 USD") String currency,
            ExchangeRateDto exchangeRate,
            UUID accountId,
            @Schema(description = "OK | NO_FORECAST(월말 예상이 없어 계산하지 않음)") String status,
            @Schema(example = "20") int trafficGrowthPercent,
            int selectedCount,
            List<SelectedDto> selected,
            @Schema(description = "고른 추천의 월 절감액 합 (트래픽 증가 반영 전)", example = "132000")
                    BigDecimal selectedMonthlySavings,
            @Schema(description = "월말 예상 (대시보드와 같은 값). NO_FORECAST면 null", example = "312000")
                    BigDecimal forecastMonthEndCost,
            @Schema(description = "적용 전 = 월말 예상 × (1 + 증가율). NO_FORECAST면 null") CostDto before,
            @Schema(description = "적용 후 = (월말 예상 − 절감액) × (1 + 증가율). NO_FORECAST면 null") CostDto after,
            @Schema(description = "매달 절감 = 적용 전 − 적용 후", example = "158400") BigDecimal monthlySavings,
            @Schema(description = "연 절감 = 매달 절감 × 12", example = "1900800") BigDecimal yearlySavings,
            BudgetDto budget,
            @Schema(description = "적용 후가 예산 안인지. 예산이 없거나 비교할 수 없으면 null") Boolean withinBudget,
            FreshnessDto freshness,
            List<String> notes,
            Instant asOf) {

        public static SimulationResponse of(SimulationView v, Resolved currency, String dataSource) {
            DisplayCurrency d = currency.display();
            CostSimulation s = v.simulation();
            List<String> notes = new ArrayList<>();
            if (UsageDtos.DATA_SOURCE_DEMO.equals(dataSource)) {
                notes.add(DemoFleet.NOTE);
                notes.add(DemoFleet.PRICE_NOTE);
            }
            notes.addAll(v.notes());
            notes.add(CostNotes.ESTIMATE);
            if (d.conversionNote() != null) {
                notes.add(d.conversionNote());
            }
            notes.addAll(currency.warnings());
            BigDecimal selectedSavings = v.selected().stream().map(WasteFinding::monthlySavingsUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new SimulationResponse(
                    dataSource, CostNotes.COST_BASIS, d.currency().name(), ExchangeRateDto.of(d), v.accountId(),
                    v.status().name(), v.trafficGrowthPercent(), v.selected().size(),
                    v.selected().stream().map(f -> SelectedDto.of(f, d)).toList(), d.cost(selectedSavings),
                    s == null ? null : d.cost(s.monthEndUsd()),
                    s == null ? null : new CostDto(d.cost(s.beforeUsd()), s.beforeBudgetRate()),
                    s == null ? null : new CostDto(d.cost(s.afterUsd()), s.afterBudgetRate()),
                    s == null ? null : d.cost(s.monthlySavingsUsd()),
                    s == null ? null : d.cost(s.yearlySavingsUsd()),
                    budget(v.budget(), s, d), s == null ? null : s.withinBudget(),
                    FreshnessDto.of(v.freshness()), List.copyOf(notes), v.asOf());
        }

        private static BudgetDto budget(MonthlyBudget b, CostSimulation s, DisplayCurrency d) {
            if (b == null) {
                return null;
            }
            BigDecimal amount = b.currency() == d.currency() ? b.amount()
                    : s == null || s.budgetUsd() == null ? null : d.cost(s.budgetUsd());
            return new BudgetDto(amount, b.amount(), b.currency().name());
        }
    }
}
