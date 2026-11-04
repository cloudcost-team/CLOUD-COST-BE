package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.CurrencyService.Resolved;
import com.cloudcost.application.WasteService.WasteView;
import com.cloudcost.domain.budget.MonthEndForecast;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.domain.ec2.InstanceSpecs.Spec;
import com.cloudcost.domain.usage.UtilizationStats;
import com.cloudcost.domain.waste.WasteDetector;
import com.cloudcost.domain.waste.WasteFinding;
import com.cloudcost.domain.waste.WasteKind;
import com.cloudcost.domain.waste.WasteSort;
import com.cloudcost.entrypoint.api.dto.UsageDtos.ExchangeRateDto;
import com.cloudcost.entrypoint.api.dto.UsageDtos.FreshnessDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 낭비 리소스 응답 (S07 목록 · S07-2 추천 상세 · S07-3 상태). 상세는 목록 항목에 모두 들어 있어 따로 부르지 않는다.
 * 금액은 요청한 통화로 환산한 추정치다. 프론트는 계산하지 않는다.
 */
public final class WasteDtos {

    private WasteDtos() {}

    public record WindowDto(
            @Schema(description = "판단 구간 시작 (포함)") Instant from,
            @Schema(description = "판단 구간 끝 (제외)") Instant to) {}

    public record CountsDto(long oversized, long idle) {}

    public record MetricsDto(
            @Schema(description = "최근 14일 CPU 평균 (%), 켜져 있던 시간 가중", example = "6.12") BigDecimal cpuAvg,
            @Schema(description = "최근 14일 CPU P95 (%), 1시간 평균값 기준", example = "9.00") BigDecimal cpuP95,
            @Schema(description = "최근 14일 CPU 최대 (%)", example = "23.00") BigDecimal cpuMax,
            @Schema(description = "최근 14일 네트워크 입출력 합계 (바이트). 수집 전이면 null") Long networkBytes,
            @Schema(description = "CPU 지표가 있는 시간 수 (켜져 있던 시간)") int cpuHours) {

        static MetricsDto of(UtilizationStats s) {
            return new MetricsDto(s.cpuAvg(), s.cpuP95(), s.cpuMax(), s.networkBytes(), s.cpuHours());
        }
    }

    @Schema(description = "S07-2 사양 비교의 한 열")
    public record SpecDto(
            String instanceType,
            @Schema(description = "카탈로그에 없으면 null") Integer vcpu,
            @Schema(description = "GiB. 카탈로그에 없으면 null", example = "8") BigDecimal memoryGib,
            @Schema(description = "시간당 단가 (currency 기준)", example = "162.1") BigDecimal hourlyCost,
            @Schema(description = "730시간 기준 월 비용 (사양 비교용)", example = "118300") BigDecimal monthlyCostAt730h) {

        static SpecDto of(String type, Spec spec, BigDecimal hourlyUsd, DisplayCurrency d) {
            return new SpecDto(type, spec == null ? null : spec.vcpu(), spec == null ? null : spec.memoryGib(),
                    d.hourlyRate(hourlyUsd), d.cost(hourlyUsd.multiply(WasteDetector.HOURS_PER_MONTH)));
        }
    }

    public record ComparisonDto(SpecDto current, @Schema(description = "유휴(중지 검토)면 null") SpecDto recommended) {}

    public record ItemDto(
            @Schema(description = "추천 id. 시뮬레이터·벤치마킹에 그대로 보낸다", example = "i-0abc:OVERSIZED") String recommendationId,
            String instanceId,
            String name,
            String instanceType,
            String state,
            @Schema(description = "OVERSIZED(과다 사양) | IDLE(유휴)") WasteKind kind,
            @Schema(example = "과다 사양") String kindLabel,
            @Schema(description = "탐지 근거", example = "최근 14일 CPU 평균 6% (P95 9%)") String evidence,
            @Schema(description = "추천 조치", example = "m5.large → t3.medium 으로 변경 검토") String action,
            @Schema(description = "추천 이유 (계산 근거 한 줄)", example = "바꿔도 P95 9%가 t3.medium 기본 성능 20% 안 · 조건에 맞는 타입 중 가장 저렴")
                    String reason,
            @Schema(description = "메모리가 줄어드는 추천이면 true (메모리는 CloudWatch 기본 지표에 없어 직접 확인 필요)")
                    boolean memoryCheckNeeded,
            MetricsDto metrics,
            @Schema(description = "최근 14일 사용 시간을 월로 환산한 시간 (최대 730)") BigDecimal monthlyHours,
            @Schema(description = "지금 월 추정 비용 (currency 기준)", example = "118300") BigDecimal estimatedMonthlyCost,
            @Schema(description = "매달 절감 예상 (currency 기준)", example = "65700") BigDecimal estimatedMonthlySavings,
            ComparisonDto comparison,
            @Schema(description = "바꾸기 전에 확인할 것") List<String> cautions,
            @Schema(description = "AWS 콘솔에서 직접 바꾸는 방법") List<String> consoleSteps) {

        static ItemDto of(WasteFinding f, DisplayCurrency d) {
            String type = f.instance().instanceType();
            SpecDto current = SpecDto.of(type, f.currentSpec(), f.hourlyUsd(), d);
            SpecDto recommended = f.targetSpec() == null ? null
                    : SpecDto.of(f.targetSpec().instanceType(), f.targetSpec(), f.targetHourlyUsd(), d);
            return new ItemDto(f.recommendationId(), f.instance().instanceId(), f.instance().name(), type,
                    f.instance().state(), f.kind(), f.kind().label(), f.evidence(), f.action(), f.reason(),
                    f.memoryCheckNeeded(), MetricsDto.of(f.stats()),
                    f.monthlyHours(), d.cost(f.monthlyCostUsd()), d.cost(f.monthlySavingsUsd()),
                    new ComparisonDto(current, recommended), f.cautions(), f.consoleSteps());
        }
    }

    public record SkippedDto(String instanceId, String name, String reason) {}

    public record WasteResponse(
            String dataSource,
            String costBasis,
            @Schema(description = "금액 통화: KRW(기본) 또는 USD") String currency,
            ExchangeRateDto exchangeRate,
            UUID accountId,
            @Schema(description = "OK(추천 있음) | NONE_FOUND(줄일 만한 서버 없음) | INSUFFICIENT_HISTORY(기록 14일 미만)")
                    String status,
            @Schema(description = "INSUFFICIENT_HISTORY일 때 추천을 보여 주기 시작하는 날", example = "2026-11-11")
                    LocalDate availableAt,
            WindowDto window,
            @Schema(description = "모든 추천을 적용했을 때 매달 절감 예상", example = "132000") BigDecimal totalMonthlySavings,
            @Schema(description = "월말 예상 비용 (대시보드와 같은 값). 끝난 날 기록이 없으면 null", example = "312000")
                    BigDecimal forecastMonthEndCost,
            @Schema(description = "절감 예상 ÷ 월말 예상 (%). 월말 예상이 없으면 null", example = "42.3")
                    BigDecimal savingsRateOfForecast,
            int findingCount,
            CountsDto counts,
            @Schema(description = "판단한 서버 수 (최근 14일 동안 24시간 이상 켜져 있던 서버)") int judgedCount,
            @Schema(description = "적용한 정렬: SAVINGS(절감액 큰 순, 기본) · COST(월 추정 비용 큰 순) · UTILIZATION(평균 CPU 낮은 순) · NAME")
                    String sort,
            @Schema(description = "적용한 종류 필터 (OVERSIZED | IDLE). 없으면 null") String type,
            @Schema(description = "정렬·필터를 적용한 추천 목록. 합계·건수(counts)는 필터와 상관없이 전체 기준") List<ItemDto> items,
            @Schema(description = "판단하지 않았거나 추천하지 못한 서버와 이유") List<SkippedDto> skipped,
            FreshnessDto freshness,
            List<String> notes,
            Instant asOf) {

        /**
         * @param forecast 대시보드의 월말 예상 (없으면 null)
         */
        public static WasteResponse of(
                WasteView view, MonthEndForecast forecast, Resolved currency, String dataSource) {
            return of(view, forecast, currency, dataSource, WasteSort.SAVINGS, null);
        }

        public static WasteResponse of(
                WasteView view, MonthEndForecast forecast, Resolved currency, String dataSource, WasteSort sort,
                WasteKind type) {
            DisplayCurrency d = currency.display();
            WasteDetector.Result r = view.result();
            List<String> notes = new ArrayList<>();
            if (UsageDtos.DATA_SOURCE_DEMO.equals(dataSource)) {
                notes.add(DemoFleet.NOTE);
                notes.add(DemoFleet.PRICE_NOTE);
            }
            notes.addAll(view.notes());
            notes.add(CostNotes.ESTIMATE);
            notes.add(CostNotes.EXCLUDED);
            String conversion = d.conversionNote();
            if (conversion != null) {
                notes.add(conversion);
            }
            notes.addAll(currency.warnings());
            return new WasteResponse(
                    dataSource, CostNotes.COST_BASIS, d.currency().name(), ExchangeRateDto.of(d), view.accountId(),
                    view.status().name(), view.availableAt(), new WindowDto(view.windowStart(), view.windowEnd()),
                    d.cost(r.totalMonthlySavingsUsd()), forecast == null ? null : d.cost(forecast.monthEndUsd()),
                    savingsRate(r.totalMonthlySavingsUsd(), forecast), r.findings().size(),
                    new CountsDto(r.count(WasteKind.OVERSIZED), r.count(WasteKind.IDLE)), r.judgedCount(),
                    sort.name(), type == null ? null : type.name(),
                    sort.apply(r.findings(), type).stream().map(f -> ItemDto.of(f, d)).toList(),
                    r.skipped().stream().map(s -> new SkippedDto(s.instanceId(), s.name(), s.reason())).toList(),
                    FreshnessDto.of(view.freshness()), List.copyOf(notes), view.asOf());
        }

        private static BigDecimal savingsRate(BigDecimal savingsUsd, MonthEndForecast forecast) {
            if (forecast == null || forecast.monthEndUsd().signum() <= 0) {
                return null;
            }
            return savingsUsd.multiply(BigDecimal.valueOf(100))
                    .divide(forecast.monthEndUsd(), 1, java.math.RoundingMode.HALF_UP);
        }
    }
}
