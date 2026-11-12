package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.CurrencyService.Resolved;
import com.cloudcost.application.WasteService.WasteView;
import com.cloudcost.domain.budget.MonthEndForecast;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.domain.ec2.InstanceSpecs.Spec;
import com.cloudcost.domain.usage.CpuSeries;
import com.cloudcost.domain.usage.UtilizationStats;
import com.cloudcost.domain.waste.WasteDetector;
import com.cloudcost.domain.waste.WasteFinding;
import com.cloudcost.domain.waste.WasteKind;
import com.cloudcost.domain.waste.WasteSort;
import com.cloudcost.entrypoint.api.dto.UsageDtos.ExchangeRateDto;
import com.cloudcost.entrypoint.api.dto.UsageDtos.FreshnessDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    @Schema(description = "S07-2 10초 요약 한 줄 (굵은 제목 · 설명)")
    public record SummaryLineDto(
            @Schema(example = "거의 안 쓰고 있어요") String title,
            @Schema(example = "14일 평균 CPU 6%, 가장 높을 때도 23%예요") String detail) {}

    @Schema(description = "조건을 만족하는 다음으로 싼 타입")
    public record CandidateDto(
            @Schema(example = "t3.large") String instanceType,
            @Schema(description = "시간당 단가 (currency 기준)", example = "144.2") BigDecimal hourlyCost) {}

    @Schema(description = "과다 사양 추천의 계산 근거. 유휴면 null")
    public record BasisDto(
            @Schema(description = "바꾼 뒤 예상 P95 (%) = 현재 P95 × 현재 vCPU ÷ 새 vCPU", example = "9.0") BigDecimal projectedCpuP95,
            @Schema(description = "추천 타입의 기본 성능 (%)", example = "20") BigDecimal targetBaselinePercent,
            @Schema(description = "다음 후보. 조건을 만족하는 다른 타입이 없으면 null") CandidateDto nextCandidate) {

        static BasisDto of(WasteFinding.DownsizeBasis b, DisplayCurrency d) {
            if (b == null) {
                return null;
            }
            return new BasisDto(b.projectedCpuP95(), b.targetBaselinePercent(), b.nextSpec() == null ? null
                    : new CandidateDto(b.nextSpec().instanceType(), d.hourlyRate(b.nextHourlyUsd())));
        }
    }

    @Schema(description = "그래프의 한 점 (CPU 지표가 있는 시간만. 꺼져 있던 시간은 없다)")
    public record CpuPointDto(
            @Schema(description = "시간 시작 (UTC)") Instant hour,
            @Schema(description = "그 시간 평균 (%)", example = "5.80") BigDecimal avg,
            @Schema(description = "그 시간 최대 (%)", example = "7.10") BigDecimal max) {}

    @Schema(description = "S07-2 탐지 근거 · 최근 14일 CPU 사용률 그래프")
    public record CpuChartDto(
            List<CpuPointDto> points,
            @Schema(description = "가장 높았던 값 (%) = metrics.cpuMax", example = "23.00") BigDecimal max,
            @Schema(description = "가장 높았던 시간 (UTC). 같은 값이면 앞선 시간") Instant maxAt,
            @Schema(description = "점선 기준선 = 추천 타입 기본 성능 (%). 유휴면 null", example = "20") BigDecimal baselinePercent,
            @Schema(description = "기준선 이름. 유휴면 null", example = "t3.medium 기본 성능 20%") String baselineLabel) {

        static CpuChartDto of(CpuSeries series, WasteFinding f) {
            if (series == null) {
                return null;
            }
            BigDecimal baseline = f.basis() == null ? null : f.basis().targetBaselinePercent();
            return new CpuChartDto(
                    series.points().stream().map(p -> new CpuPointDto(p.hour(), p.avg(), p.max())).toList(),
                    series.max(), series.maxAt(), baseline,
                    baseline == null ? null : f.targetSpec().instanceType() + " 기본 성능 " + plain(baseline) + "%");
        }
    }

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
            @Schema(description = "S07-2 10초 요약 (3줄)") List<SummaryLineDto> summary,
            @Schema(description = "10초 요약 아래 경고 한 줄. 메모리가 줄어드는 추천일 때만, 아니면 null",
                    example = "메모리(8 → 4 GiB)는 확인하지 못했어요. 바꾸기 전에 서버에서 직접 확인해 주세요.")
                    String summaryNote,
            BasisDto basis,
            CpuChartDto cpuChart,
            MetricsDto metrics,
            @Schema(description = "최근 14일 사용 시간을 월로 환산한 시간 (최대 730)") BigDecimal monthlyHours,
            @Schema(description = "지금 월 추정 비용 (currency 기준)", example = "118300") BigDecimal estimatedMonthlyCost,
            @Schema(description = "매달 절감 예상 (currency 기준)", example = "65700") BigDecimal estimatedMonthlySavings,
            ComparisonDto comparison,
            @Schema(description = "바꾸기 전에 확인할 것") List<String> cautions,
            @Schema(description = "AWS 콘솔에서 직접 바꾸는 방법") List<String> consoleSteps) {

        static ItemDto of(WasteFinding f, DisplayCurrency d, CpuSeries series) {
            String type = f.instance().instanceType();
            SpecDto current = SpecDto.of(type, f.currentSpec(), f.hourlyUsd(), d);
            SpecDto recommended = f.targetSpec() == null ? null
                    : SpecDto.of(f.targetSpec().instanceType(), f.targetSpec(), f.targetHourlyUsd(), d);
            return new ItemDto(f.recommendationId(), f.instance().instanceId(), f.instance().name(), type,
                    f.instance().state(), f.kind(), f.kind().label(), f.evidence(), f.action(), f.reason(),
                    f.memoryCheckNeeded(), summary(f, d), summaryNote(f), BasisDto.of(f.basis(), d),
                    CpuChartDto.of(series, f), MetricsDto.of(f.stats()),
                    f.monthlyHours(), d.cost(f.monthlyCostUsd()), d.cost(f.monthlySavingsUsd()),
                    new ComparisonDto(current, recommended), f.cautions(), f.consoleSteps());
        }

        /**
         * 10초 요약. 제목은 사용량 구간에 따라 바뀌고(아래), 설명은 서버마다 다른 숫자를 적는다. 금액은 요청 통화로 적는다.
         * <ul>
         *   <li>사용량: 평균(유휴는 최대) CPU 1% 미만 "사실상 쉬고 있어요" · 8% 미만 "거의 안 쓰고 있어요" · 그 밖 "적게 쓰고 있어요"
         *   <li>여유(과다 사양): 예상 사용률이 기본 성능의 절반 이하 "줄여도 넉넉해요" · 그 밖 "줄여도 기본 성능 안이에요"
         *   <li>가격(과다 사양): 다음 후보가 있으면 "조건을 만족하는 것 중 가장 싸요" · 없으면 "조건을 만족하는 타입은 이것뿐이에요"
         *   <li>네트워크(유휴): 하루 평균 1MB 미만 "네트워크도 거의 없어요" · 그 밖 "네트워크도 조용해요"
         * </ul>
         */
        static List<SummaryLineDto> summary(WasteFinding f, DisplayCurrency d) {
            UtilizationStats s = f.stats();
            String max = WasteFinding.percent(s.cpuMax());
            if (f.kind() == WasteKind.IDLE) {
                BigDecimal dailyMb = BigDecimal.valueOf(s.networkBytes())
                        .divide(BigDecimal.valueOf(UtilizationStats.WINDOW.toDays() * 1024L * 1024L), 1, java.math.RoundingMode.HALF_UP);
                String network = dailyMb.signum() == 0 ? "0.1MB 미만이에요" : plain(dailyMb) + "MB예요";
                return List.of(
                        new SummaryLineDto(usageTitle(s.cpuMax()), "14일 동안 CPU가 가장 높을 때도 " + max + ending(max)),
                        new SummaryLineDto(dailyMb.compareTo(BigDecimal.ONE) < 0 ? "네트워크도 거의 없어요" : "네트워크도 조용해요",
                                "하루 평균 입출력이 " + network),
                        new SummaryLineDto("중지하면 그만큼 줄어요",
                                "매달 " + money(d.cost(f.monthlySavingsUsd()), d) + " 절감 예상 (추정)"));
            }
            WasteFinding.DownsizeBasis b = f.basis();
            String projected = WasteFinding.percent(b.projectedCpuP95());
            boolean roomy = b.projectedCpuP95().multiply(BigDecimal.valueOf(2)).compareTo(b.targetBaselinePercent()) <= 0;
            String price = "시간당 " + money(d.hourlyRate(f.targetHourlyUsd()), d);
            if (b.nextSpec() != null) {
                price += " (다음 후보 " + b.nextSpec().instanceType() + " " + money(d.hourlyRate(b.nextHourlyUsd()), d) + ")";
            }
            boolean spiky = s.cpuMax() != null && s.cpuMax().compareTo(SPIKY_MAX) >= 0;
            return List.of(
                    new SummaryLineDto(usageTitle(s.cpuAvg()), "14일 평균 CPU " + WasteFinding.percent(s.cpuAvg())
                            + (spiky ? ", 가장 높을 땐 " + max + "까지 올라갔어요" : ", 가장 높을 때도 " + max + ending(max))),
                    new SummaryLineDto(roomy ? "줄여도 넉넉해요" : "줄여도 기본 성능 안이에요",
                            "바꿔도 예상 사용률 " + projected + ", 기본 성능(" + plain(b.targetBaselinePercent()) + "%) 안이에요"),
                    new SummaryLineDto(b.nextSpec() != null ? "조건을 만족하는 것 중 가장 싸요" : "조건을 만족하는 타입은 이것뿐이에요",
                            price));
        }

        /** 최대 CPU가 이 이상이면 "가장 높을 때도"(낮다는 뉘앙스) 대신 "까지 올라갔어요"로 적는다 */
        static final BigDecimal SPIKY_MAX = BigDecimal.valueOf(50);

        private static String usageTitle(BigDecimal cpu) {
            if (cpu.compareTo(BigDecimal.ONE) < 0) {
                return "사실상 쉬고 있어요";
            }
            return cpu.compareTo(BigDecimal.valueOf(8)) < 0 ? "거의 안 쓰고 있어요" : "적게 쓰고 있어요";
        }

        static String summaryNote(WasteFinding f) {
            if (!f.memoryCheckNeeded()) {
                return null;
            }
            return "메모리(" + plain(f.currentSpec().memoryGib()) + " → " + plain(f.targetSpec().memoryGib())
                    + " GiB)는 확인하지 못했어요. 바꾸기 전에 서버에서 직접 확인해 주세요.";
        }

        /** "23%예요" · "1% 미만이에요" */
        private static String ending(String percent) {
            return percent.endsWith("미만") ? "이에요" : "예요";
        }
    }

    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.ROOT);

    /** ₩72.1 · ₩65,700 · $0.0416 (이미 통화에 맞게 반올림한 값) */
    static String money(BigDecimal amount, DisplayCurrency d) {
        if (amount == null) {
            return "-";
        }
        if (d.currency() == com.cloudcost.domain.cost.DisplayCurrency.Currency.KRW) {
            return "₩" + new DecimalFormat("#,##0.#", SYMBOLS).format(amount);
        }
        return "$" + new DecimalFormat("#,##0.00##", SYMBOLS).format(amount);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
            @Schema(description = "절감 예상 ÷ 현재 월 비용 (%, 최대 100). 분모는 currentMonthlyCost로 절감액과 같은 기준이다 "
                    + "(이번 달 월말 예상이 아니다 — 이번 달 중간에 켠 서버에서 100%를 넘지 않게). 판단한 서버가 없으면 null",
                    example = "42.3") BigDecimal savingsRateOfForecast,
            @Schema(description = "판단한 서버를 지금처럼(최근 14일 사용 시간) 한 달 쓰면 드는 비용. 절감 비율의 분모",
                    example = "312000") BigDecimal currentMonthlyCost,
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
            Instant asOf,
            @Schema(description = "방치 리소스·잊힌 리전 (ADR-020). 위 items(서버 사양·유휴)와 따로 센다. "
                    + "권한이 부족하면 scan.status=PERMISSION_UPGRADE_REQUIRED와 upgrade 안내")
                    ResourceDtos.ResourceWasteDto resources) {

        /** 방치 리소스를 붙인 응답 */
        public WasteResponse withResources(ResourceDtos.ResourceWasteDto resources) {
            return new WasteResponse(dataSource, costBasis, currency, exchangeRate, accountId, status, availableAt, window,
                    totalMonthlySavings, forecastMonthEndCost, savingsRateOfForecast, currentMonthlyCost, findingCount,
                    counts, judgedCount,
                    sort, type, items, skipped, freshness, notes, asOf, resources);
        }

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
                    r.savingsRatePercent(),
                    r.judgedMonthlyCostUsd().signum() > 0 ? d.cost(r.judgedMonthlyCostUsd()) : null, r.findings().size(),
                    new CountsDto(r.count(WasteKind.OVERSIZED), r.count(WasteKind.IDLE)), r.judgedCount(),
                    sort.name(), type == null ? null : type.name(),
                    sort.apply(r.findings(), type).stream()
                            .map(f -> ItemDto.of(f, d, view.cpuSeries().get(f.instance().instanceId()))).toList(),
                    r.skipped().stream().map(s -> new SkippedDto(s.instanceId(), s.name(), s.reason())).toList(),
                    FreshnessDto.of(view.freshness()), List.copyOf(notes), view.asOf(), null);
        }
    }
}
