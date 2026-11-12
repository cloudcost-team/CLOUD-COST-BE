package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.BenchmarkService;
import com.cloudcost.application.BenchmarkService.BenchmarkView;
import com.cloudcost.application.BenchmarkService.OversizedSection;
import com.cloudcost.application.BenchmarkService.SpendSection;
import com.cloudcost.application.BenchmarkService.WasteSection;
import com.cloudcost.application.CurrencyService.Resolved;
import com.cloudcost.domain.benchmark.BenchmarkReference;
import com.cloudcost.domain.benchmark.BenchmarkReference.SizeBand;
import com.cloudcost.domain.benchmark.SpendBenchmark;
import com.cloudcost.domain.benchmark.TeamBenchmark;
import com.cloudcost.domain.benchmark.TeamBenchmark.DistributionBucket;
import com.cloudcost.domain.benchmark.TeamBenchmark.Ratio;
import com.cloudcost.domain.benchmark.TeamBenchmark.ServerVerdict;
import com.cloudcost.domain.benchmark.WasteRatio;
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

    @Schema(description = "분포 막대 한 칸")
    public record DistributionBucketDto(
            @Schema(description = "과다 사양 비율 하한 (%, 포함)", example = "20") BigDecimal fromPercent,
            @Schema(description = "상한 (%). 마지막 칸(80~100)만 100을 포함", example = "40") BigDecimal toPercent,
            @Schema(example = "20~40%") String label,
            @Schema(description = "이 칸에 드는 비교 팀 비율 (%). 칸을 모두 더하면 100", example = "17.5") BigDecimal teamsPercent,
            @Schema(description = "우리 팀 과다 사양 비율이 이 칸이면 true") boolean ours,
            @Schema(description = "추천 적용 후 비율이 이 칸이면 true (recommendationIds가 없으면 모두 false)")
                    boolean afterSimulation) {}

    @Schema(description = "비교 팀의 과다 사양 비율 분포 (reference 구간). 백분위 표가 없거나 비교 구간이 없으면 null")
    public record DistributionDto(
            @Schema(description = "원본이 아니라 백분위 표로 어림한 값이면 true") boolean approximate,
            @Schema(description = "분포를 만든 방법") String basis,
            @Schema(description = "분포를 만든 구간 키 (reference.sizeBand와 같다)", example = "3-20") String sizeBand,
            @Schema(description = "0~20 · 20~40 · 40~60 · 60~80 · 80~100% 다섯 칸") List<DistributionBucketDto> buckets,
            @Schema(description = "우리 팀이 드는 칸 번호 (0부터). 우리 비율이 없으면 null", example = "3") Integer oursBucketIndex,
            @Schema(description = "추천 적용 후 드는 칸 번호. 적용 후가 없으면 null", example = "0")
                    Integer afterSimulationBucketIndex) {

        static final String BASIS = "백분위 표(5% 간격)로 어림한 분포라 칸마다 5%p 정도 틀릴 수 있어요.";

        static DistributionDto of(OversizedSection s) {
            SizeBand band = s.selectedBand();
            if (band == null || !band.hasData() || band.percentiles().isEmpty()) {
                return null;
            }
            List<DistributionBucket> buckets = TeamBenchmark.distribution(band.percentiles());
            Integer ours = bucket(s.ratio());
            Integer after = bucket(s.after());
            List<DistributionBucketDto> rows = new java.util.ArrayList<>();
            for (int i = 0; i < buckets.size(); i++) {
                DistributionBucket b = buckets.get(i);
                rows.add(new DistributionBucketDto(b.fromPercent(), b.toPercent(),
                        b.fromPercent().toPlainString() + "~" + b.toPercent().toPlainString() + "%", b.teamsPercent(),
                        ours != null && ours == i, after != null && after == i));
            }
            return new DistributionDto(true, BASIS, band.key(), List.copyOf(rows), ours, after);
        }

        private static Integer bucket(Ratio r) {
            return r == null || r.ratioPercent() == null ? null : TeamBenchmark.bucketIndex(r.ratioPercent());
        }
    }

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
            DistributionDto distribution,
            @Schema(description = "서버별 P95와 판정 (P95 낮은 순)") List<ServerDto> servers,
            @Schema(description = "추천을 적용한 뒤 (recommendationIds가 없으면 null)") RatioDto afterSimulation) {

        static OversizedDto of(OversizedSection s, BenchmarkReference.Oversized ref) {
            SizeBand matched = s.matchedBand();
            List<SizeBandDto> bands = ref.bands().stream()
                    .map(b -> new SizeBandDto(b.key(), b.label(), b.minServers(), b.maxServers(), b.hasData(), b == matched))
                    .toList();
            return new OversizedDto(s.status().name(), RatioDto.of(s.ratio()), s.moreEfficientTeamsPercent(),
                    matched == null ? null : matched.key(), OversizedReferenceDto.of(ref, s.selectedBand()), bands,
                    DistributionDto.of(s), s.servers().stream().map(ServerDto::of).toList(), RatioDto.of(s.after()));
        }
    }

    @Schema(description = "② 전체 낭비 비율 금액 (currency 기준과 USD)")
    public record WasteAmountsDto(
            @Schema(description = "서버 낭비(과다 사양·유휴 추천)의 월 절감액 합") BigDecimal serverWaste,
            @Schema(description = "방치 리소스(볼륨·고정 IP·스냅샷·LB·잊힌 리전 서버) 월 비용 합") BigDecimal resourceWaste,
            @Schema(description = "낭비 금액 = serverWaste + resourceWaste") BigDecimal waste,
            @Schema(description = "전체 월 비용 = 월말 예상 + resourceWaste (비율의 분모)") BigDecimal total,
            @Schema(example = "24.10") BigDecimal wasteUsd,
            @Schema(example = "80.25") BigDecimal totalUsd) {

        static WasteAmountsDto of(WasteRatio r, DisplayCurrency d) {
            return new WasteAmountsDto(d.cost(r.serverWasteUsd()), d.cost(r.resourceWasteUsd()), d.cost(r.wasteUsd()),
                    d.cost(r.totalUsd()), usd(r.wasteUsd()), usd(r.totalUsd()));
        }
    }

    @Schema(description = "② 전체 낭비 비율 (S09). 방치 리소스 비용을 모르면 UNAVAILABLE + 사유")
    public record WasteRatioDto(
            @Schema(description = "OK | UNAVAILABLE(권한 부족·확인 중·실패·단가 모름) | INSUFFICIENT_HISTORY | NO_FORECAST",
                    example = "OK") String status,
            @Schema(description = "OK가 아닐 때 사유 (한국어), OK면 null") String reason,
            @Schema(description = "전체 낭비 비율 (%). OK가 아니면 null", example = "30.0") BigDecimal ratioPercent,
            @Schema(description = "참고 평균 대비: BELOW | SAME | ABOVE. OK가 아니면 null") String comparison,
            @Schema(description = "OK가 아니면 null") WasteAmountsDto amounts,
            @Schema(description = "추천 적용 후 비율 (%). recommendationIds가 없거나 OK가 아니면 null (방치 리소스는 그대로라고 본다)",
                    example = "12.4") BigDecimal afterSimulationRatioPercent,
            @Schema(description = "참고값 출처", example = "Flexera 2026 State of the Cloud") String referenceSource,
            String referenceDescription,
            @Schema(description = "참고 평균 (%)", example = "29") BigDecimal referenceAverage) {

        static WasteRatioDto of(WasteSection s, BenchmarkReference.Waste ref, DisplayCurrency d) {
            WasteRatio r = s.ratio();
            WasteRatio.Comparison c = r == null ? null : r.compareTo(ref.averagePercent());
            return new WasteRatioDto(s.status().name(), s.reason(), r == null ? null : r.ratioPercent(),
                    c == null ? null : c.name(), r == null ? null : WasteAmountsDto.of(r, d),
                    s.after() == null ? null : s.after().ratioPercent(), ref.source(), ref.description(),
                    ref.averagePercent());
        }
    }

    @Schema(description = "비교 모델 정보 (학습·검증 결과)")
    public record SpendModelDto(
            @Schema(example = "QUANTILE_REGRESSION") String method,
            @Schema(description = "학습한 팀(구독) 수", example = "5452") int sampleSize,
            @Schema(description = "학습한 서버 대수 범위", example = "1") int minServers,
            @Schema(example = "50") int maxServers,
            @Schema(description = "검증에 쓴 시험용 팀 수", example = "1118") int testSize,
            @Schema(description = "기존 방식(서버 대수 구간별 백분위 표)보다 시험용 오차(pinball loss)가 줄어든 비율 (%)", example = "15.3")
                    BigDecimal improvementPercent,
            @Schema(description = "예측한 일반 범위(25~75번째)에 시험용 실제 값이 들어온 비율 (%, 잘 맞으면 50 근처)", example = "51.9")
                    BigDecimal middleHalfCoveragePercent) {

        static SpendModelDto of(BenchmarkReference.SpendModel m) {
            return new SpendModelDto("QUANTILE_REGRESSION", m.sampleSize(), m.minServers(), m.maxServers(),
                    m.validation().testSize(), m.validation().improvementPercent(),
                    m.validation().middleHalfCoveragePercent());
        }
    }

    @Schema(description = "같은 규모(우리 서버 대수) 팀의 일반 범위 (가운데 절반 = 25~75번째 백분위). 금액은 모델이 예측한 사용량을 우리 팀의 vCPU-시간당 비용으로 환산한 값")
    public record SpendRangeDto(
            String source,
            String description,
            @Schema(description = "비교한 서버 대수 (우리 서버 수)", example = "2") int servers,
            @Schema(example = "서버 2대") String label,
            @Schema(description = "범위 하한 (currency 기준)") BigDecimal min,
            @Schema(description = "범위 상한 (currency 기준)") BigDecimal max,
            @Schema(example = "8.54") BigDecimal minUsd,
            @Schema(example = "25.36") BigDecimal maxUsd,
            @Schema(description = "범위 하한 사용량 (vCPU-시간)", example = "1165.8") BigDecimal minVcpuHours,
            @Schema(description = "범위 상한 사용량 (vCPU-시간)", example = "3463.4") BigDecimal maxVcpuHours,
            @Schema(description = "같은 규모 팀 사용량 중앙값 (vCPU-시간)", example = "2880.0") BigDecimal medianVcpuHours,
            SpendModelDto model) {}

    public record SpendAfterDto(
            @Schema(description = "적용 후 월 비용 (currency 기준)") BigDecimal monthlyCost,
            @Schema(description = "적용 후 월 비용 (USD)", example = "79.64") BigDecimal monthlyCostUsd,
            @Schema(example = "WITHIN") String position,
            @Schema(description = "범위 막대 위 위치 (%, 0=하한 100=상한, 범위 밖이면 0 또는 100)", example = "0.0")
                    BigDecimal barPositionPercent) {}

    @Schema(description = "③ 규모 대비 월 지출 (S09-4)")
    public record SpendDto(
            @Schema(description = "OK | NO_FORECAST | INSUFFICIENT_HISTORY(기록 14일 미만) | NOT_COMPARABLE(켜진 서버나 맞는 구간 자료가 없음)")
                    String status,
            @Schema(description = "월말 예상 (currency 기준)", example = "52635") BigDecimal monthlyCost,
            @Schema(description = "월말 예상 (USD, 범위 minUsd·maxUsd와 같은 단위)", example = "36.10") BigDecimal monthlyCostUsd,
            @Schema(description = "한 달로 환산한 우리 사용량 (vCPU-시간). INSUFFICIENT_HISTORY·NO_FORECAST면 null", example = "2880.0")
                    BigDecimal monthlyVcpuHours,
            @Schema(description = "사용량을 센 서버 수", example = "2") Integer servers,
            @Schema(description = "같은 규모 팀 중 우리보다 확실히 적게 쓰는 팀 (%, 5% 단위 하한). OK가 아니면 null", example = "60.0")
                    BigDecimal teamsUsingLessPercent,
            @Schema(description = "BELOW(범위 아래) | WITHIN | ABOVE. OK가 아니면 null") String position,
            @Schema(description = "범위 막대 위 우리 위치 (%, 0=하한 100=상한, 범위 밖이면 0 또는 100). OK가 아니면 null",
                    example = "24.5") BigDecimal barPositionPercent,
            @Schema(description = "OK가 아니면 null") SpendRangeDto range,
            @Schema(description = "추천을 적용한 뒤 (recommendationIds가 없거나 OK가 아니면 null)") SpendAfterDto afterSimulation) {

        static SpendDto of(SpendSection s, BenchmarkReference.Spend ref, DisplayCurrency d) {
            SpendRangeDto range = s.range() == null ? null
                    : new SpendRangeDto(ref.source(), ref.description(), s.usage().servers(),
                            "서버 " + s.usage().servers() + "대", d.cost(s.range().minUsd()), d.cost(s.range().maxUsd()),
                            usd(s.range().minUsd()), usd(s.range().maxUsd()),
                            SpendBenchmark.at(s.table(), SpendBenchmark.LOW_PERCENTILE),
                            SpendBenchmark.at(s.table(), SpendBenchmark.HIGH_PERCENTILE),
                            SpendBenchmark.at(s.table(), SpendBenchmark.MEDIAN_PERCENTILE),
                            SpendModelDto.of(ref.model()));
            SpendAfterDto after = s.afterUsd() == null ? null
                    : new SpendAfterDto(d.cost(s.afterUsd()), usd(s.afterUsd()), s.afterPosition().name(),
                            SpendBenchmark.barPositionPercent(s.afterUsd(), s.range()));
            return new SpendDto(s.status().name(), d.cost(s.monthEndUsd()), usd(s.monthEndUsd()),
                    s.usage() == null ? null : s.usage().vcpuHours(),
                    s.usage() == null ? null : s.usage().servers(),
                    s.teamsUsingLessPercent(),
                    s.position() == null ? null : s.position().name(),
                    s.range() == null ? null : SpendBenchmark.barPositionPercent(s.monthEndUsd(), s.range()), range, after);
        }

    }

    /** 달러 표시 금액 (센트 단위) */
    private static BigDecimal usd(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
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
            @Schema(description = "고른 추천의 월 절감액 합 (currency 기준, 시뮬레이션의 selectedMonthlySavings와 같은 값). "
                    + "recommendationIds가 없으면 null", example = "132000") BigDecimal selectedMonthlySavings,
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
                    v.applied().isEmpty() ? null : d.cost(v.applied().stream().map(WasteFinding::monthlySavingsUsd)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)),
                    OversizedDto.of(v.oversized(), ref.oversized()),
                    WasteRatioDto.of(v.waste(), ref.waste(), d),
                    SpendDto.of(v.spend(), ref.spend(), d), FreshnessDto.of(v.freshness()), List.copyOf(notes),
                    v.asOf());
        }
    }
}
