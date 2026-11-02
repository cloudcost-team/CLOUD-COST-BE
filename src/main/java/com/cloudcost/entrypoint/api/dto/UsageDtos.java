package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.CurrencyService.Resolved;
import com.cloudcost.application.Ec2UsageQueryService.InstanceRow;
import com.cloudcost.application.Ec2UsageQueryService.InstancesView;
import com.cloudcost.application.Ec2UsageQueryService.UsageCostView;
import com.cloudcost.application.Freshness;
import com.cloudcost.domain.cost.CostEstimator;
import com.cloudcost.domain.cost.CostEstimator.InstanceCost;
import com.cloudcost.domain.cost.CostEstimator.PeriodCost;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.cost.InstancePrice;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.usage.CurrentRun;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 응답 DTO. 금액은 모두 추정치이며 dataSource·costBasis를 함께 준다.
 * 금액은 요청한 통화(기본 KRW)로 환산해 주고, 사용한 환율을 exchangeRate로 함께 준다. 프론트는 계산하지 않는다.
 */
public final class UsageDtos {

    public static final String DATA_SOURCE_AWS = "aws";
    public static final String DATA_SOURCE_DEMO = "demo";

    private UsageDtos() {}

    @Schema(description = "이번 응답이 AWS에서 새로 가져온 것인지 DB에 저장된 것인지")
    public record FreshnessDto(
            Instant instancesRefreshedAt,
            boolean instancesFromCache,
            @Schema(description = "마지막 CloudWatch 호출 시각") Instant usageRefreshedAt,
            @Schema(description = "true면 이번 응답의 사용량은 DB에 저장된 값") boolean usageFromCache,
            @Schema(description = "이 시각 전까지는 CloudWatch를 다시 부르지 않는다 (1시간 1회)") Instant nextUsageRefreshAt,
            List<String> warnings) {

        static FreshnessDto of(Freshness f) {
            return new FreshnessDto(f.instancesRefreshedAt(), f.instancesFromCache(), f.usageRefreshedAt(),
                    f.usageFromCache(), f.nextUsageRefreshAt(), f.warnings());
        }
    }

    @Schema(description = "원화 환산에 쓴 환율 (USD로 조회하면 null)")
    public record ExchangeRateDto(
            @Schema(example = "USD") String base,
            @Schema(example = "KRW") String quote,
            @Schema(example = "1333.09") BigDecimal rate,
            @Schema(description = "환율 기준일", example = "2026-10-12") LocalDate date,
            String source) {

        static ExchangeRateDto of(DisplayCurrency d) {
            return d.rate() == null ? null
                    : new ExchangeRateDto("USD", "KRW", d.rate().usdToKrw(), d.rate().rateDate(), d.rate().source());
        }
    }

    public record CurrentRunDto(
            @Schema(description = "현재 실행 구간 시작 (LaunchTime)") Instant since,
            BigDecimal hours,
            String note) {}

    public record InstanceDto(
            String instanceId,
            String name,
            String instanceType,
            String region,
            String availabilityZone,
            String state,
            Instant launchTime,
            String platformDetails,
            String tenancy,
            boolean spot,
            @Schema(description = "마지막 DescribeInstances 결과에 있었는지 (삭제되면 false, 기록은 유지)") boolean present,
            CurrentRunDto currentRun,
            @Schema(description = "온디맨드 시간당 단가 (currency 기준). 찾지 못하면 null", example = "55.5") BigDecimal estimatedHourlyCost,
            String note) {}

    public record InstancesResponse(
            String dataSource,
            String costBasis,
            @Schema(description = "금액 통화: KRW(기본) 또는 USD") String currency,
            ExchangeRateDto exchangeRate,
            UUID accountId,
            Instant asOf,
            List<InstanceDto> instances,
            FreshnessDto freshness,
            List<String> notes) {

        public static InstancesResponse of(InstancesView view, Resolved currency) {
            return of(view, currency, DATA_SOURCE_AWS);
        }

        public static InstancesResponse of(InstancesView view, Resolved currency, String dataSource) {
            DisplayCurrency d = currency.display();
            List<InstanceDto> rows = view.rows().stream().map(r -> instance(r, d)).toList();
            List<String> notes = new ArrayList<>();
            if (DATA_SOURCE_DEMO.equals(dataSource)) {
                notes.add(DemoFleet.NOTE);
                notes.add(DemoFleet.PRICE_NOTE);
            }
            notes.addAll(List.of(CostNotes.ESTIMATE, CostNotes.EXCLUDED, CurrentRun.NOTE));
            return new InstancesResponse(
                    dataSource, CostNotes.COST_BASIS, d.currency().name(), ExchangeRateDto.of(d), view.accountId(),
                    view.asOf(), rows, FreshnessDto.of(view.freshness()), withCurrencyNotes(notes, currency));
        }
    }

    public record InstanceUsageDto(
            String instanceId,
            String name,
            String instanceType,
            String state,
            BigDecimal usageHours,
            BigDecimal estimatedHourlyCost,
            @Schema(description = "추정 비용. 단가를 찾지 못하면 null (0이 아님)") BigDecimal estimatedCost,
            String note) {}

    public record CostSummaryResponse(
            String dataSource,
            String costBasis,
            @Schema(description = "금액 통화: KRW(기본) 또는 USD") String currency,
            ExchangeRateDto exchangeRate,
            String timezone,
            LocalDate from,
            LocalDate to,
            BigDecimal usageHours,
            BigDecimal estimatedCost,
            List<InstanceUsageDto> instances,
            FreshnessDto freshness,
            List<String> notes) {

        public static CostSummaryResponse of(UsageCostView<?> view, String timezone, Resolved currency) {
            return of(view, timezone, currency, DATA_SOURCE_AWS);
        }

        public static CostSummaryResponse of(
                UsageCostView<?> view, String timezone, Resolved currency, String dataSource) {
            DisplayCurrency d = currency.display();
            PeriodCost<?> period = view.report().periods().getFirst();
            return new CostSummaryResponse(
                    dataSource, CostNotes.COST_BASIS, d.currency().name(), ExchangeRateDto.of(d), timezone,
                    view.range().from(), view.range().to(), hours(view.report().totalMinutes()),
                    d.cost(view.report().totalEstimatedCost()), instanceRows(view, period, d),
                    FreshnessDto.of(view.freshness()), withCurrencyNotes(view.notes(), currency));
        }
    }

    public record DailyCostDto(
            LocalDate date, BigDecimal usageHours, BigDecimal estimatedCost, List<InstanceUsageDto> instances) {}

    public record DailyCostResponse(
            String dataSource,
            String costBasis,
            @Schema(description = "금액 통화: KRW(기본) 또는 USD") String currency,
            ExchangeRateDto exchangeRate,
            String timezone,
            LocalDate from,
            LocalDate to,
            BigDecimal usageHours,
            BigDecimal estimatedCost,
            List<DailyCostDto> days,
            FreshnessDto freshness,
            List<String> notes) {

        public static DailyCostResponse of(UsageCostView<LocalDate> view, String timezone, Resolved currency) {
            return of(view, timezone, currency, DATA_SOURCE_AWS);
        }

        public static DailyCostResponse of(
                UsageCostView<LocalDate> view, String timezone, Resolved currency, String dataSource) {
            DisplayCurrency d = currency.display();
            List<DailyCostDto> days = view.report().periods().stream()
                    .map(p -> new DailyCostDto(p.period(), hours(p.usedMinutes()), d.cost(p.estimatedCost()),
                            instanceRows(view, p, d)))
                    .toList();
            return new DailyCostResponse(
                    dataSource, CostNotes.COST_BASIS, d.currency().name(), ExchangeRateDto.of(d), timezone,
                    view.range().from(), view.range().to(), hours(view.report().totalMinutes()),
                    d.cost(view.report().totalEstimatedCost()), days, FreshnessDto.of(view.freshness()),
                    withCurrencyNotes(view.notes(), currency));
        }
    }

    public record MonthlyCostDto(
            @Schema(example = "2026-10") String month,
            BigDecimal usageHours,
            BigDecimal estimatedCost,
            List<InstanceUsageDto> instances) {}

    public record MonthlyCostResponse(
            String dataSource,
            String costBasis,
            @Schema(description = "금액 통화: KRW(기본) 또는 USD") String currency,
            ExchangeRateDto exchangeRate,
            String timezone,
            String from,
            String to,
            BigDecimal usageHours,
            BigDecimal estimatedCost,
            List<MonthlyCostDto> months,
            FreshnessDto freshness,
            List<String> notes) {

        public static MonthlyCostResponse of(UsageCostView<java.time.YearMonth> view, String timezone, Resolved currency) {
            return of(view, timezone, currency, DATA_SOURCE_AWS);
        }

        public static MonthlyCostResponse of(
                UsageCostView<java.time.YearMonth> view, String timezone, Resolved currency, String dataSource) {
            DisplayCurrency d = currency.display();
            List<MonthlyCostDto> months = view.report().periods().stream()
                    .map(p -> new MonthlyCostDto(p.period().toString(), hours(p.usedMinutes()), d.cost(p.estimatedCost()),
                            instanceRows(view, p, d)))
                    .toList();
            return new MonthlyCostResponse(
                    dataSource, CostNotes.COST_BASIS, d.currency().name(), ExchangeRateDto.of(d), timezone,
                    java.time.YearMonth.from(view.range().from()).toString(),
                    java.time.YearMonth.from(view.range().to()).toString(),
                    hours(view.report().totalMinutes()), d.cost(view.report().totalEstimatedCost()), months,
                    FreshnessDto.of(view.freshness()), withCurrencyNotes(view.notes(), currency));
        }
    }

    private static List<String> withCurrencyNotes(List<String> base, Resolved currency) {
        List<String> notes = new ArrayList<>(base);
        String conversion = currency.display().conversionNote();
        if (conversion != null) {
            notes.add(conversion);
        }
        notes.addAll(currency.warnings());
        return List.copyOf(notes);
    }

    private static InstanceDto instance(InstanceRow row, DisplayCurrency d) {
        TrackedInstance t = row.instance();
        var info = t.info();
        CurrentRunDto run = row.currentRun() == null
                ? null
                : new CurrentRunDto(row.currentRun().since(), row.currentRun().hours(), CurrentRun.NOTE);
        InstancePrice price = row.price();
        return new InstanceDto(
                info.instanceId(), info.name(), info.instanceType(), info.region(), info.availabilityZone(), info.state(),
                info.launchTime(), info.platformDetails(), info.tenancy(), info.spot(), t.present(), run,
                price == null ? null : d.hourlyRate(price.hourlyUsd()), price == null ? CostNotes.PRICE_NOT_FOUND : price.note());
    }

    private static List<InstanceUsageDto> instanceRows(UsageCostView<?> view, PeriodCost<?> period, DisplayCurrency d) {
        return period.instances().stream().map(c -> instanceUsage(view, c, d)).toList();
    }

    private static InstanceUsageDto instanceUsage(UsageCostView<?> view, InstanceCost c, DisplayCurrency d) {
        TrackedInstance t = view.instances().get(c.instanceId());
        InstancePrice price = view.prices().get(c.instanceId());
        String note = price == null ? CostNotes.PRICE_NOT_FOUND : price.note();
        return new InstanceUsageDto(
                c.instanceId(),
                t == null ? null : t.info().name(),
                t == null ? null : t.info().instanceType(),
                t == null ? null : t.info().state(),
                hours(c.usedMinutes()),
                d.hourlyRate(c.hourlyUsd()),
                d.cost(c.estimatedCost()),
                note);
    }

    static BigDecimal hours(long minutes) {
        return CostEstimator.hours(minutes).setScale(2, RoundingMode.HALF_UP);
    }
}
