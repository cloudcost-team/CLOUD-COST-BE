package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.CurrencyService.Resolved;
import com.cloudcost.application.DashboardService.BudgetStatus;
import com.cloudcost.application.DashboardService.DashboardView;
import com.cloudcost.application.DashboardService.LatestSpike;
import com.cloudcost.application.DashboardService.PeriodTotal;
import com.cloudcost.application.DashboardService.PreviousComparison;
import com.cloudcost.domain.budget.MonthEndForecast;
import com.cloudcost.domain.budget.MonthlyBudget;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.domain.spike.SpikeDetector.Spike;
import com.cloudcost.entrypoint.api.dto.UsageDtos.ExchangeRateDto;
import com.cloudcost.entrypoint.api.dto.UsageDtos.FreshnessDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 월 예산과 대시보드 요약 (S06 상단 카드). 금액은 요청한 통화로 환산한 추정치다. 프론트는 계산하지 않는다.
 */
public final class DashboardDtos {

    private DashboardDtos() {}

    @Schema(description = "연결별 월 예산. 입력한 통화 그대로 저장한다")
    public record BudgetRequest(
            @Schema(example = "400000") @NotNull(message = "숫자로 입력해 주세요 (예: 400000)") BigDecimal amount,
            @Schema(example = "KRW") @NotNull(message = "KRW 또는 USD를 골라 주세요") Currency currency) {

        public MonthlyBudget toDomain() {
            return new MonthlyBudget(amount, currency);
        }
    }

    public record BudgetResponse(UUID accountId, BigDecimal amount, String currency) {

        public static BudgetResponse of(UUID accountId, MonthlyBudget b) {
            return new BudgetResponse(accountId, b.amount(), b.currency().name());
        }
    }

    public record MonthToDateDto(
            LocalDate from,
            @Schema(description = "오늘 (진행 중인 오늘 포함)") LocalDate to,
            BigDecimal usageHours,
            @Schema(description = "이번 달 추정 비용", example = "128400") BigDecimal estimatedCost) {}

    public record InstanceCountsDto(
            @Schema(description = "실행 중", example = "4") int running,
            @Schema(description = "중지됨·중지 중", example = "2") int stopped) {}

    @Schema(description = "월말 예상 비용. 끝난 날 기록이 없으면 null")
    public record ForecastDto(
            @Schema(example = "312000") BigDecimal monthEndCost,
            @Schema(description = "이번 달 1일 ~ 어제 추정 비용") BigDecimal costThroughYesterday,
            @Schema(description = "평균에 쓴 하루 추정 비용") BigDecimal dailyAverage,
            LocalDate basisFrom,
            LocalDate basisTo,
            @Schema(description = "평균에 쓴 날 수 (최대 7)") int basisDays,
            @Schema(description = "오늘을 포함해 말일까지 남은 날") int remainingDays) {

        static ForecastDto of(MonthEndForecast f, DisplayCurrency d) {
            return f == null ? null : new ForecastDto(d.cost(f.monthEndUsd()), d.cost(f.costThroughYesterdayUsd()),
                    d.cost(f.dailyAverageUsd()), f.basisFrom(), f.basisTo(), f.basisDays(), f.remainingDays());
        }
    }

    @Schema(description = "월 예산. 정하지 않았으면 null")
    public record BudgetDto(
            @Schema(description = "예산 (currency 기준). 원화 예산인데 환율이 없어 달러로 바꿀 수 없으면 null", example = "400000")
                    BigDecimal amount,
            @Schema(description = "사용자가 입력한 금액", example = "400000") BigDecimal enteredAmount,
            @Schema(example = "KRW") String enteredCurrency,
            @Schema(description = "월말 예상 ÷ 예산 (%). 계산할 수 없으면 null", example = "78.0") BigDecimal usageRate,
            @Schema(description = "넘은 가장 높은 알림 기준 80·100·120 (월말 예상 기준). 없으면 null", example = "80")
                    Integer thresholdReached) {

        static BudgetDto of(BudgetStatus b, DisplayCurrency d) {
            if (b == null) {
                return null;
            }
            BigDecimal amount = b.budget().currency() == d.currency()
                    ? b.budget().amount()
                    : d.cost(b.budgetUsd());
            return new BudgetDto(amount, b.budget().amount(), b.budget().currency().name(), b.budgetRate(),
                    b.thresholdReached());
        }
    }

    @Schema(description = "이번 달 1일~어제 vs 전월 1일~같은 날 (끝난 날끼리). 비교할 수 없으면 null")
    public record PreviousMonthDto(
            LocalDate currentFrom,
            LocalDate currentTo,
            BigDecimal currentCost,
            LocalDate previousFrom,
            LocalDate previousTo,
            @Schema(example = "114600") BigDecimal previousCost,
            @Schema(description = "증감 (반올림한 금액끼리의 차)", example = "13800") BigDecimal change,
            @Schema(description = "증감률 (%). 전월이 0이면 null", example = "12.0") BigDecimal changeRate) {

        static PreviousMonthDto of(PreviousComparison p, DisplayCurrency d) {
            if (p == null) {
                return null;
            }
            BigDecimal current = d.cost(p.current().costUsd());
            BigDecimal previous = d.cost(p.previous().costUsd());
            return new PreviousMonthDto(p.current().from(), p.current().to(), current, p.previous().from(),
                    p.previous().to(), previous, current.subtract(previous), p.changeRate());
        }
    }

    @Schema(description = "최근 30일(오늘 포함) 중 가장 최근 급증일 (S06 배너). 없으면 null")
    public record LatestSpikeDto(
            @Schema(example = "2026-10-12") LocalDate date,
            @Schema(description = "그날 추정 비용", example = "9840") BigDecimal cost,
            @Schema(description = "그날 비용 ÷ 평소", example = "2.4") BigDecimal ratio,
            @Schema(description = "평소 하루 비용 (직전 30일 중앙값)", example = "4100") BigDecimal baseline,
            @Schema(description = "평소를 잰 날 수 (7~30)") int baselineDays,
            String causeInstanceId,
            @Schema(example = "web-server-1") String causeName,
            @Schema(example = "10/12 비용 급증 · 평소의 2.4배 · web-server-1 사용 시간이 늘었어요") String message) {

        static LatestSpikeDto of(LatestSpike latest, DisplayCurrency d) {
            if (latest == null) {
                return null;
            }
            Spike s = latest.spike();
            return new LatestSpikeDto(s.date(), d.cost(s.costUsd()), s.ratio(), d.cost(s.baselineUsd()),
                    s.baselineDays(), s.causeInstanceId(), latest.causeName(), s.message(latest.causeName()));
        }
    }

    public record DashboardResponse(
            String dataSource,
            String costBasis,
            @Schema(description = "금액 통화: KRW(기본) 또는 USD") String currency,
            ExchangeRateDto exchangeRate,
            String timezone,
            UUID accountId,
            LocalDate today,
            @Schema(example = "2026-11") String month,
            LocalDate availableFrom,
            MonthToDateDto monthToDate,
            InstanceCountsDto instanceCounts,
            ForecastDto forecast,
            BudgetDto budget,
            PreviousMonthDto previousMonthSamePeriod,
            LatestSpikeDto latestSpike,
            FreshnessDto freshness,
            List<String> notes,
            Instant asOf) {

        public static DashboardResponse of(DashboardView v, String timezone, Resolved currency, String dataSource) {
            DisplayCurrency d = currency.display();
            PeriodTotal mtd = v.monthToDate();
            List<String> notes = new ArrayList<>();
            if (UsageDtos.DATA_SOURCE_DEMO.equals(dataSource)) {
                notes.add(DemoFleet.NOTE);
                notes.add("체험 모드는 설정한 기간의 마지막 날을 오늘로 보고 계산합니다.");
            }
            notes.addAll(v.notes());
            if (d.conversionNote() != null) {
                notes.add(d.conversionNote());
            }
            notes.addAll(currency.warnings());
            return new DashboardResponse(
                    dataSource, CostNotes.COST_BASIS, d.currency().name(), ExchangeRateDto.of(d), timezone,
                    v.accountId(), v.today(), v.month().toString(), v.availableFrom(),
                    new MonthToDateDto(mtd.from(), mtd.to(), UsageDtos.hours(mtd.usedMinutes()), d.cost(mtd.costUsd())),
                    new InstanceCountsDto(v.runningCount(), v.stoppedCount()),
                    ForecastDto.of(v.forecast(), d), BudgetDto.of(v.budget(), d), PreviousMonthDto.of(v.previous(), d),
                    LatestSpikeDto.of(v.latestSpike(), d), FreshnessDto.of(v.freshness()), List.copyOf(notes), v.asOf());
        }
    }
}
