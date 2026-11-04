package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.BenchmarkService;
import com.cloudcost.application.BenchmarkService.BenchmarkView;
import com.cloudcost.application.BenchmarkService.OversizedSection;
import com.cloudcost.application.BenchmarkService.SpendSection;
import com.cloudcost.application.CurrencyService.Resolved;
import com.cloudcost.domain.benchmark.BenchmarkReference;
import com.cloudcost.domain.benchmark.BenchmarkReference.SizeBand;
import com.cloudcost.domain.benchmark.TeamBenchmark.Ratio;
import com.cloudcost.domain.benchmark.TeamBenchmark.ServerVerdict;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.domain.waste.WasteFinding;
import com.cloudcost.entrypoint.api.dto.UsageDtos.ExchangeRateDto;
import com.cloudcost.entrypoint.api.dto.UsageDtos.FreshnessDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 팀 벤치마킹 (S09 · S09-2~4). 금액은 요청한 통화로 환산한 추정치다. 프론트는 계산하지 않는다.
 */
public final class BenchmarkDtos {

    private BenchmarkDtos() {}

    public record ServerDto(
            String instanceId,
            String name,
            String instanceType,
            @Schema(description = "최근 14일 CPU P95 (%), 1시간 평균값 기준", example = "6.20") BigDecimal cpuP95,
            @Schema(description = "CPU 지표가 있는 시간 수 (켜져 있던 시간)") int cpuHours,
            boolean oversized,
            @Schema(example = "과다 사양 (P95 10% 미만)") String verdict) {

        static ServerDto of(ServerVerdict v) {
            return new ServerDto(v.instance().instanceId(), v.instance().name(), v.instance().instanceType(), v.cpuP95(),
                    v.cpuHours(), v.oversized(), v.verdictLabel());
        }
    }

    public record RatioDto(
            @Schema(description = "판단한 서버 수") int judgedCount,
            int oversizedCount,
            @Schema(description = "과다 사양 비율 (%). 판단한 서버가 없으면 null", example = "50.0") BigDecimal ratio) {

        static RatioDto of(Ratio r) {
            return r == null ? null : new RatioDto(r.judgedCount(), r.oversizedCount(), r.ratioPercent());
        }
    }

    @Schema(description = "비교에 쓴 서버(VM) 대수 구간의 분포")
    public record OversizedReferenceDto(
            String source,
            String description,
            @Schema(description = "구간 키 (sizeBand로 보낸다)", example = "1-2") String sizeBand,
            @Schema(example = "서버 1~2대") String sizeBandLabel,
            @Schema(description = "이 구간의 비교 자료가 있으면 true. false면 숫자가 null이고 status는 NOT_COMPARABLE")
                    boolean dataAvailable,
            int sampleSize,
            @Schema(example = "42") BigDecimal averageRatio,
            @Schema(example = "35") BigDecimal medianRatio,
            @Schema(description = "백분위 표가 있으면 true (없으면 moreEfficientTeamsPercent는 null)") boolean percentileAvailable) {

        static OversizedReferenceDto of(BenchmarkReference.Oversized ref, SizeBand band) {
            if (band == null) {
                return new OversizedReferenceDto(ref.source(), ref.description(), null, null, false, 0, null, null, false);
            }
            return new OversizedReferenceDto(ref.source(), ref.description(), band.key(), band.label(), band.hasData(),
                    band.sampleSize(), band.averagePercent(), band.medianPercent(), !band.percentiles().isEmpty());
        }
    }

    @Schema(description = "고를 수 있는 서버 대수 구간 (S09 규모 선택)")
    public record SizeBandDto(
            @Schema(example = "1-2") String key,
            @Schema(example = "서버 1~2대") String label,
            int minServers,
            int maxServers,
            @Schema(description = "비교 자료가 있으면 true") boolean dataAvailable,
            @Schema(description = "우리 서버 대수가 속한 구간이면 true") boolean matchesOurs) {}

    @Schema(description = "① 과다 사양 서버 비율 (S09-2·S09-3)")
    public record OversizedDto(
            @Schema(description = "OK | NOT_COMPARABLE(판단한 서버가 없거나 고른 구간의 비교 자료 없음) | INSUFFICIENT_HISTORY(기록 14일 미만)")
                    String status,
            @Schema(description = "우리 비율. INSUFFICIENT_HISTORY면 null") RatioDto ours,
            @Schema(description = "우리보다 과다 사양 비율이 낮은 팀 (%). 백분위 표가 없거나 비교하지 않으면 null")
                    BigDecimal moreEfficientTeamsPercent,
            @Schema(description = "우리 서버 대수가 속한 구간 키. 판단한 서버가 없거나 맞는 구간이 없으면 null", example = "1-2")
                    String matchedSizeBand,
            @Schema(description = "비교에 쓴 구간 (요청한 sizeBand, 없으면 matchedSizeBand)") OversizedReferenceDto reference,
            @Schema(description = "고를 수 있는 구간 (작은 순)") List<SizeBandDto> sizeBands,
            @Schema(description = "서버별 P95와 판정 (P95 낮은 순)") List<ServerDto> servers,
            @Schema(description = "추천을 적용한 뒤 (recommendationIds가 없으면 null)") RatioDto afterSimulation) {

        static OversizedDto of(OversizedSection s, BenchmarkReference.Oversized ref) {
            SizeBand matched = s.matchedBand();
            List<SizeBandDto> bands = ref.bands().stream()
                    .map(b -> new SizeBandDto(b.key(), b.label(), b.minServers(), b.maxServers(), b.hasData(), b == matched))
                    .toList();
            return new OversizedDto(s.status().name(), RatioDto.of(s.ratio()), s.moreEfficientTeamsPercent(),
                    matched == null ? null : matched.key(), OversizedReferenceDto.of(ref, s.selectedBand()), bands,
                    s.servers().stream().map(ServerDto::of).toList(), RatioDto.of(s.after()));
        }
    }

    @Schema(description = "② 전체 낭비 비율. 지금 권한으로는 계산할 수 없어 참고값만 준다")
    public record WasteRatioDto(
            @Schema(example = "UNAVAILABLE") String status,
            String reason,
            @Schema(description = "참고값 출처", example = "Flexera 2026 State of the Cloud") String referenceSource,
            String referenceDescription,
            @Schema(description = "참고 평균 (%)", example = "29") BigDecimal referenceAverage) {}

    public record SpendRangeDto(
            String source,
            String description,
            @Schema(description = "범위 하한 (currency 기준)") BigDecimal min,
            @Schema(description = "범위 상한 (currency 기준)") BigDecimal max,
            @Schema(example = "100") BigDecimal minUsd,
            @Schema(example = "500") BigDecimal maxUsd) {}

    public record SpendAfterDto(BigDecimal monthlyCost, @Schema(example = "WITHIN") String position) {}

    @Schema(description = "③ 월 지출 (S09-4)")
    public record SpendDto(
            @Schema(description = "OK | NO_FORECAST") String status,
            @Schema(description = "월말 예상 (currency 기준)", example = "312000") BigDecimal monthlyCost,
            @Schema(description = "BELOW(범위 아래) | WITHIN | ABOVE. NO_FORECAST면 null") String position,
            SpendRangeDto range,
            @Schema(description = "추천을 적용한 뒤 (recommendationIds가 없으면 null)") SpendAfterDto afterSimulation) {

        static SpendDto of(SpendSection s, BenchmarkReference.Spend ref, DisplayCurrency d) {
            SpendRangeDto range = new SpendRangeDto(ref.source(), ref.description(), d.cost(ref.minUsd()),
                    d.cost(ref.maxUsd()), ref.minUsd(), ref.maxUsd());
            SpendAfterDto after = s.afterUsd() == null ? null
                    : new SpendAfterDto(d.cost(s.afterUsd()), s.afterPosition().name());
            return new SpendDto(s.status().name(), d.cost(s.monthEndUsd()),
                    s.position() == null ? null : s.position().name(), range, after);
        }
    }

    public record BenchmarkResponse(
            String dataSource,
            String costBasis,
            @Schema(description = "금액 통화: KRW(기본) 또는 USD") String currency,
            ExchangeRateDto exchangeRate,
            UUID accountId,
            @Schema(description = "OK | INSUFFICIENT_HISTORY(기록 14일 미만, ①을 비교하지 않음)") String status,
            @Schema(description = "INSUFFICIENT_HISTORY일 때 비교를 시작하는 날") LocalDate availableAt,
            WasteDtos.WindowDto window,
            @Schema(description = "적용한다고 본 추천 id") List<String> appliedRecommendationIds,
            OversizedDto oversizedRatio,
            WasteRatioDto wasteRatio,
            SpendDto monthlySpend,
            FreshnessDto freshness,
            List<String> notes,
            Instant asOf) {

        public static BenchmarkResponse of(BenchmarkView v, Resolved currency, String dataSource) {
            DisplayCurrency d = currency.display();
            BenchmarkReference ref = v.reference();
            // 대시보드 안내와 겹치는 문구는 한 번만 보여 준다
            java.util.Set<String> notes = new java.util.LinkedHashSet<>();
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
            return new BenchmarkResponse(
                    dataSource, CostNotes.COST_BASIS, d.currency().name(), ExchangeRateDto.of(d), v.accountId(),
                    v.status().name(), v.availableAt(), new WasteDtos.WindowDto(v.windowStart(), v.windowEnd()),
                    v.applied().stream().map(WasteFinding::recommendationId).toList(),
                    OversizedDto.of(v.oversized(), ref.oversized()),
                    new WasteRatioDto(v.wasteStatus().name(),
                            BenchmarkService.WASTE_UNAVAILABLE_REASON, ref.waste().source(),
                            ref.waste().description(), ref.waste().averagePercent()),
                    SpendDto.of(v.spend(), ref.spend(), d), FreshnessDto.of(v.freshness()), List.copyOf(notes),
                    v.asOf());
        }
    }
}
