package com.cloudcost.application;

import com.cloudcost.application.DashboardService.DashboardView;
import com.cloudcost.application.WasteService.WasteView;
import com.cloudcost.application.port.BenchmarkReferenceSource;
import com.cloudcost.domain.DomainValidationException;
import com.cloudcost.domain.benchmark.BenchmarkReference;
import com.cloudcost.domain.benchmark.BenchmarkReference.SizeBand;
import com.cloudcost.domain.benchmark.BenchmarkReference.SpendBand;
import com.cloudcost.domain.benchmark.SpendBenchmark;
import com.cloudcost.domain.benchmark.TeamBenchmark;
import com.cloudcost.domain.benchmark.TeamBenchmark.Position;
import com.cloudcost.domain.benchmark.TeamBenchmark.Ratio;
import com.cloudcost.domain.benchmark.TeamBenchmark.ServerVerdict;
import com.cloudcost.domain.demo.DemoSetup;
import com.cloudcost.domain.simulation.CostSimulation;
import com.cloudcost.domain.waste.WasteFinding;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 팀 벤치마킹 (이슈 #33, S09). 낭비 탐지(#10)의 최근 14일 사용률과 대시보드 월말 예상(#27)을 비교 기준과 견준다.
 * 추천 id를 주면 시뮬레이터(#31)와 같은 계산으로 적용 후 위치도 준다. 실제 연결과 체험 모드가 같은 {@link #compose}를 쓴다.
 */
public class BenchmarkService {

    public enum Status {
        OK,
        /** 판단한 서버가 없거나, 고른 서버 대수 구간의 비교 자료가 없어 비율을 비교하지 않음 */
        NOT_COMPARABLE,
        /** 기록 시작일부터 14일이 안 됨 */
        INSUFFICIENT_HISTORY,
        /** 지금 권한으로 계산할 수 없음 */
        UNAVAILABLE,
        /** 월말 예상이 없음 */
        NO_FORECAST
    }

    public static final String WASTE_UNAVAILABLE_REASON =
        "전체 낭비 비율을 구하려면 미연결 볼륨·스냅샷·고정 IP·로드밸런서 비용까지 알아야 하는데, "
            + "지금 연결 권한(EC2 인스턴스 목록·CloudWatch 지표 읽기)으로는 볼 수 없어 계산하지 않아요.";
    public static final String NO_PERCENTILES =
        "비교 자료에 백분위 표가 없어 \"우리보다 효율적인 팀 %\"는 계산하지 않았어요. 평균·중앙값과 비교해 주세요.";

    /**
     * @param after        추천을 적용한 뒤 (추천 id를 주지 않았으면 null)
     * @param matchedBand  우리 서버 대수가 속한 구간 (판단한 서버가 없거나 어느 구간에도 안 들면 null)
     * @param selectedBand 비교에 쓴 구간 (요청한 구간, 없으면 matchedBand)
     */
    public record OversizedSection(
        Status status, List<ServerVerdict> servers, Ratio ratio, BigDecimal moreEfficientTeamsPercent, Ratio after,
        SizeBand matchedBand, SizeBand selectedBand) {
    }

    /**
     * ③ 규모 대비 월 지출. OK가 아니면 usage·band·range·순위·위치 중 일부가 null이다.
     *
     * @param status                OK · NO_FORECAST(월말 예상 없음) · INSUFFICIENT_HISTORY(기록 14일 미만이라 사용량을 못 잼)
     *                              · NOT_COMPARABLE(켜진 서버가 없거나 맞는 구간·자료가 없음)
     * @param monthEndUsd           월말 예상 (NO_FORECAST면 null)
     * @param usage                 우리 한 달 사용량 (INSUFFICIENT_HISTORY·NO_FORECAST면 null)
     * @param band                  비교에 쓴 서버 대수 구간
     * @param teamsUsingLessPercent 같은 규모 팀 중 우리보다 확실히 적게 쓰는 팀 (%)
     * @param range                 같은 규모 팀의 일반 범위(25~75번째 백분위)를 우리 vCPU-시간당 비용으로 환산한 금액
     * @param afterUsd              추천 적용 후 월 비용 (추천 id를 주지 않았거나 OK가 아니면 null)
     */
    public record SpendSection(
        Status status, BigDecimal monthEndUsd, SpendBenchmark.Usage usage, SpendBand band,
        BigDecimal teamsUsingLessPercent, SpendBenchmark.UsdRange range, Position position, BigDecimal afterUsd,
        Position afterPosition) {

        static SpendSection notCompared(Status status, BigDecimal monthEndUsd, SpendBenchmark.Usage usage, SpendBand band) {
            return new SpendSection(status, monthEndUsd, usage, band, null, null, null, null, null);
        }
    }

    /**
     * @param status      INSUFFICIENT_HISTORY(사용률 비교 불가) 또는 OK
     * @param availableAt INSUFFICIENT_HISTORY일 때 비교를 시작하는 날
     * @param applied     적용한다고 본 추천 (요청 순서)
     */
    public record BenchmarkView(
        UUID accountId,
        Status status,
        LocalDate availableAt,
        Instant windowStart,
        Instant windowEnd,
        List<WasteFinding> applied,
        OversizedSection oversized,
        Status wasteStatus,
        SpendSection spend,
        BenchmarkReference reference,
        Freshness freshness,
        List<String> notes,
        Instant asOf) {
    }

    private final WasteService waste;
    private final DashboardService dashboards;
    private final DemoUsageService demo;
    private final BenchmarkReferenceSource references;
    private final Clock clock;

    public BenchmarkService(
        WasteService waste, DashboardService dashboards, DemoUsageService demo, BenchmarkReferenceSource references,
        Clock clock) {
        this.waste = waste;
        this.dashboards = dashboards;
        this.demo = demo;
        this.references = references;
        this.clock = clock;
    }

    /**
     * @param sizeBand 비교할 서버 대수 구간 키(예: "1-2"). null이면 우리 서버 대수에 맞는 구간
     */
    public BenchmarkView benchmark(String ownerId, UUID accountId, List<String> recommendationIds, String sizeBand) {
        BenchmarkReference reference = references.reference();
        requireKnownBand(reference, sizeBand);
        WasteView wasteView = waste.waste(ownerId, accountId);
        DashboardView dashboard = dashboards.dashboard(ownerId, accountId);
        return compose(accountId, wasteView, dashboard, recommendationIds, sizeBand, reference, clock.instant());
    }

    public BenchmarkView demoBenchmark(DemoSetup setup, List<String> recommendationIds, String sizeBand) {
        BenchmarkReference reference = references.reference();
        requireKnownBand(reference, sizeBand);
        return compose(DemoUsageService.ACCOUNT_ID, demo.waste(setup), dashboards.demoDashboard(setup, null),
            recommendationIds, sizeBand, reference, clock.instant());
    }

    /** 모르는 구간 키는 400 */
    static void requireKnownBand(BenchmarkReference reference, String sizeBand) {
        if (sizeBand != null && reference.oversized().band(sizeBand).isEmpty()) {
            String known = String.join(", ", reference.oversized().bands().stream().map(SizeBand::key).toList());
            throw new DomainValidationException("VALIDATION_ERROR",
                "sizeBand는 " + known + " 중 하나로 보내 주세요.");
        }
    }

    static BenchmarkView compose(
        UUID accountId, WasteView wasteView, DashboardView dashboard, List<String> recommendationIds, String sizeBand,
        BenchmarkReference reference, Instant asOf) {
        boolean simulate = recommendationIds != null && !recommendationIds.isEmpty();
        List<WasteFinding> applied = CostSimulation.select(wasteView.result().findings(), recommendationIds);
        // 월 지출 위치는 대시보드 월말 예상으로 잰다. 단가를 모르는 서버를 뺀 사실 등 그 계산의 안내도 함께 보여 준다
        List<String> notes = new ArrayList<>(dashboard.notes());
        notes.add(WasteService.BASIS);
        notes.add("과다 사양 = 최근 14일 동안 24시간 이상 켜진 서버 중 CPU P95가 10% 미만인 서버예요. "
            + "비교 자료는 마이크로소프트 내부 서비스 VM 기록(Azure Public Dataset)이에요. "
            + "우리는 1시간 평균, 비교 자료는 5분 최대값으로 재서 정확한 순위가 아니라 참고용으로 봐 주세요.");

        boolean insufficient = wasteView.status() == WasteService.Status.INSUFFICIENT_HISTORY;
        OversizedSection oversized = oversized(wasteView, insufficient, simulate, applied, sizeBand, reference, notes);
        if (simulate) {
            notes.add("적용 후: 과다 사양 추천을 적용한 서버는 적정, 유휴 추천(중지)을 적용한 서버는 판단 대상에서 빠진다고 봤어요.");
        }
        SpendSection spend = spend(dashboard, wasteView, insufficient, simulate, applied, sizeBand, reference, notes);
        notes.add(WASTE_UNAVAILABLE_REASON);
        return new BenchmarkView(accountId, insufficient ? Status.INSUFFICIENT_HISTORY : Status.OK,
            wasteView.availableAt(), wasteView.windowStart(), wasteView.windowEnd(), applied, oversized,
            Status.UNAVAILABLE, spend, reference, dashboard.freshness(), List.copyOf(notes), asOf);
    }

    private static OversizedSection oversized(
        WasteView wasteView, boolean insufficient, boolean simulate, List<WasteFinding> applied, String sizeBand,
        BenchmarkReference reference, List<String> notes) {
        BenchmarkReference.Oversized ref = reference.oversized();
        SizeBand requested = sizeBand == null ? null : ref.band(sizeBand).orElseThrow();
        if (insufficient) {
            notes.add("기록이 14일 미만이라 " + wasteView.availableAt() + "부터 과다 사양 비율을 비교할 수 있어요.");
            return new OversizedSection(Status.INSUFFICIENT_HISTORY, List.of(), null, null, null, null, requested);
        }
        List<ServerVerdict> verdicts = TeamBenchmark.verdicts(
            wasteView.instances(), wasteView.stats(), wasteView.result().judgedIds());
        Ratio ratio = TeamBenchmark.ratio(verdicts);
        Ratio after = simulate ? TeamBenchmark.ratioAfter(verdicts, applied) : null;
        // 우리 서버 대수에 맞는 구간과 비교한다. 다른 구간을 고르면 "그 규모 팀과 비교하면"으로 본다
        SizeBand matched = verdicts.isEmpty() ? null : ref.bandFor(verdicts.size()).orElse(null);
        SizeBand selected = requested != null ? requested : matched;
        if (verdicts.isEmpty()) {
            notes.add("최근 14일 동안 24시간 이상 켜진 서버가 없어 과다 사양 비율을 비교하지 않았어요.");
            return new OversizedSection(Status.NOT_COMPARABLE, verdicts, ratio, null, after, null, selected);
        }
        if (selected == null) {
            notes.add("판단한 서버 " + verdicts.size() + "대에 맞는 비교 구간이 없어 과다 사양 비율은 비교하지 않았어요.");
            return new OversizedSection(Status.NOT_COMPARABLE, verdicts, ratio, null, after, null, null);
        }
        if (!selected.hasData()) {
            notes.add(selected.label() + " 비교 자료는 아직 준비 중이라 과다 사양 비율은 비교하지 않았어요.");
            return new OversizedSection(Status.NOT_COMPARABLE, verdicts, ratio, null, after, matched, selected);
        }
        if (requested != null && requested != matched) {
            notes.add("우리는 서버 " + verdicts.size() + "대지만 " + selected.label() + " 팀 자료와 견줬어요.");
        }
        BigDecimal moreEfficient = TeamBenchmark.moreEfficientTeamsPercent(ratio.ratioPercent(), selected.percentiles());
        if (moreEfficient == null) {
            notes.add(NO_PERCENTILES);
        }
        return new OversizedSection(Status.OK, verdicts, ratio, moreEfficient, after, matched, selected);
    }

    private static SpendSection spend(
        DashboardView dashboard, WasteView wasteView, boolean insufficient, boolean simulate, List<WasteFinding> applied,
        String sizeBand, BenchmarkReference reference, List<String> notes) {
        if (dashboard.forecast() == null) {
            notes.add("월말 예상 비용이 아직 없어 월 지출은 비교하지 않았어요.");
            return SpendSection.notCompared(Status.NO_FORECAST, null, null, null);
        }
        BigDecimal monthEnd = dashboard.forecast().monthEndUsd();
        notes.add("월 지출은 EC2 온디맨드 추정 비용(월말 예상)이라 EBS·데이터 전송 등이 빠져 실제 청구액보다 작아요.");
        if (insufficient) {
            notes.add("기록이 14일 미만이라 " + wasteView.availableAt() + "부터 같은 규모 팀과 월 지출을 비교할 수 있어요.");
            return SpendSection.notCompared(Status.INSUFFICIENT_HISTORY, monthEnd, null, null);
        }
        BenchmarkReference.Spend ref = reference.spend();
        SpendBenchmark.Usage usage = SpendBenchmark.monthlyUsage(wasteView.instances(), wasteView.stats());
        if (!usage.unknownTypes().isEmpty()) {
            notes.add("vCPU를 모르는 타입(" + String.join(", ", usage.unknownTypes()) + ")은 월 지출 순위의 사용량에서 뺐어요.");
        }
        SpendBand band = sizeBand != null ? ref.band(sizeBand).orElse(null)
            : usage.servers() == 0 ? null : ref.bandFor(usage.servers()).orElse(null);
        if (usage.servers() == 0 || usage.vcpuHours().signum() <= 0) {
            notes.add("최근 14일 동안 켜진 서버가 없어 같은 규모 팀과 월 지출을 비교하지 않았어요.");
            return SpendSection.notCompared(Status.NOT_COMPARABLE, monthEnd, usage, band);
        }
        if (band == null || !band.hasData() || monthEnd.signum() <= 0) {
            notes.add("서버 " + usage.servers() + "대에 맞는 비교 자료가 없어 월 지출은 비교하지 않았어요.");
            return SpendSection.notCompared(Status.NOT_COMPARABLE, monthEnd, usage, band);
        }
        notes.add("월 지출 순위 = 최근 14일 켜진 시간 × vCPU를 한 달로 환산한 사용량을 " + band.label()
            + " 팀들(Azure Public Dataset)과 비교한 결과예요. 일반 범위는 그 팀들의 가운데 절반을 우리 팀의 단가로 환산한 금액이에요.");
        SpendBenchmark.UsdRange range = SpendBenchmark.typicalRange(band, monthEnd, usage.vcpuHours()).orElseThrow();
        BigDecimal less = SpendBenchmark.teamsUsingLessPercent(usage.vcpuHours(), band.percentiles());
        BigDecimal afterUsd = null;
        Position afterPosition = null;
        if (simulate) {
            afterUsd = CostSimulation.of(monthEnd, applied, 0, null).afterUsd();
            afterPosition = SpendBenchmark.position(afterUsd, range);
        }
        return new SpendSection(Status.OK, monthEnd, usage, band, less, range, SpendBenchmark.position(monthEnd, range),
            afterUsd, afterPosition);
    }
}
