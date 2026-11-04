package com.cloudcost.application;

import com.cloudcost.application.DashboardService.DashboardView;
import com.cloudcost.application.WasteService.WasteView;
import com.cloudcost.application.port.BenchmarkReferenceSource;
import com.cloudcost.domain.benchmark.BenchmarkReference;
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
        /** 판단한 서버가 3대 미만이라 비율을 비교하지 않음 */
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
     * @param after 추천을 적용한 뒤 (추천 id를 주지 않았으면 null)
     */
    public record OversizedSection(
        Status status, List<ServerVerdict> servers, Ratio ratio, BigDecimal moreEfficientTeamsPercent, Ratio after) {
    }

    /**
     * @param monthEndUsd 월말 예상 (NO_FORECAST면 null)
     * @param afterUsd    추천 적용 후 월 비용 (추천 id를 주지 않았거나 NO_FORECAST면 null)
     */
    public record SpendSection(
        Status status, BigDecimal monthEndUsd, Position position, BigDecimal afterUsd, Position afterPosition) {
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

    public BenchmarkView benchmark(String ownerId, UUID accountId, List<String> recommendationIds) {
        WasteView wasteView = waste.waste(ownerId, accountId);
        DashboardView dashboard = dashboards.dashboard(ownerId, accountId);
        return compose(accountId, wasteView, dashboard, recommendationIds, references.reference(), clock.instant());
    }

    public BenchmarkView demoBenchmark(DemoSetup setup, List<String> recommendationIds) {
        return compose(DemoUsageService.ACCOUNT_ID, demo.waste(setup), dashboards.demoDashboard(setup, null),
            recommendationIds, references.reference(), clock.instant());
    }

    static BenchmarkView compose(
        UUID accountId, WasteView wasteView, DashboardView dashboard, List<String> recommendationIds,
        BenchmarkReference reference, Instant asOf) {
        boolean simulate = recommendationIds != null && !recommendationIds.isEmpty();
        List<WasteFinding> applied = CostSimulation.select(wasteView.result().findings(), recommendationIds);
        List<String> notes = new ArrayList<>();
        notes.add(WasteService.BASIS);
        notes.add("과다 사양 = 최근 14일 동안 24시간 이상 켜진 서버 중 CPU P95가 10% 미만인 서버예요. "
            + "비교 자료와 측정 방식(지표 간격·기간)이 달라 참고용으로 봐 주세요.");

        boolean insufficient = wasteView.status() == WasteService.Status.INSUFFICIENT_HISTORY;
        OversizedSection oversized = oversized(wasteView, insufficient, simulate, applied, reference, notes);
        if (simulate) {
            notes.add("적용 후: 과다 사양 추천을 적용한 서버는 적정, 유휴 추천(중지)을 적용한 서버는 판단 대상에서 빠진다고 봤어요.");
        }
        SpendSection spend = spend(dashboard, simulate, applied, reference, notes);
        notes.add(WASTE_UNAVAILABLE_REASON);
        return new BenchmarkView(accountId, insufficient ? Status.INSUFFICIENT_HISTORY : Status.OK,
            wasteView.availableAt(), wasteView.windowStart(), wasteView.windowEnd(), applied, oversized,
            Status.UNAVAILABLE, spend, reference, dashboard.freshness(), List.copyOf(notes), asOf);
    }

    private static OversizedSection oversized(
        WasteView wasteView, boolean insufficient, boolean simulate, List<WasteFinding> applied,
        BenchmarkReference reference, List<String> notes) {
        if (insufficient) {
            notes.add("기록이 14일 미만이라 " + wasteView.availableAt() + "부터 과다 사양 비율을 비교할 수 있어요.");
            return new OversizedSection(Status.INSUFFICIENT_HISTORY, List.of(), null, null, null);
        }
        List<ServerVerdict> verdicts = TeamBenchmark.verdicts(
            wasteView.instances(), wasteView.stats(), wasteView.result().judgedIds());
        Ratio ratio = TeamBenchmark.ratio(verdicts);
        Ratio after = simulate ? TeamBenchmark.ratioAfter(verdicts, applied) : null;
        if (verdicts.size() < TeamBenchmark.MIN_SERVERS) {
            notes.add("판단한 서버가 " + TeamBenchmark.MIN_SERVERS + "대 미만이라 과다 사양 비율은 다른 팀과 비교하지 않아요 "
                + "(비교 자료는 VM 3대 이상 구독 기준).");
            return new OversizedSection(Status.NOT_COMPARABLE, verdicts, ratio, null, after);
        }
        BigDecimal moreEfficient =
            TeamBenchmark.moreEfficientTeamsPercent(ratio.ratioPercent(), reference.oversized().percentiles());
        if (moreEfficient == null) {
            notes.add(NO_PERCENTILES);
        }
        return new OversizedSection(Status.OK, verdicts, ratio, moreEfficient, after);
    }

    private static SpendSection spend(
        DashboardView dashboard, boolean simulate, List<WasteFinding> applied, BenchmarkReference reference,
        List<String> notes) {
        if (dashboard.forecast() == null) {
            notes.add("월말 예상 비용이 아직 없어 월 지출은 비교하지 않았어요.");
            return new SpendSection(Status.NO_FORECAST, null, null, null, null);
        }
        BigDecimal monthEnd = dashboard.forecast().monthEndUsd();
        BenchmarkReference.Spend range = reference.spend();
        notes.add("월 지출은 EC2 온디맨드 추정 비용(월말 예상)이라 EBS·데이터 전송 등이 빠져 실제 청구액보다 작아요.");
        BigDecimal afterUsd = null;
        Position afterPosition = null;
        if (simulate) {
            afterUsd = CostSimulation.of(monthEnd, applied, 0, null).afterUsd();
            afterPosition = TeamBenchmark.position(afterUsd, range);
        }
        return new SpendSection(Status.OK, monthEnd, TeamBenchmark.position(monthEnd, range), afterUsd, afterPosition);
    }
}
