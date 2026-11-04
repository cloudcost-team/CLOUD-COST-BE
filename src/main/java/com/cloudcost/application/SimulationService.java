package com.cloudcost.application;

import com.cloudcost.application.DashboardService.BudgetStatus;
import com.cloudcost.application.DashboardService.DashboardView;
import com.cloudcost.application.WasteService.WasteView;
import com.cloudcost.domain.budget.MonthlyBudget;
import com.cloudcost.domain.demo.DemoSetup;
import com.cloudcost.domain.simulation.CostSimulation;
import com.cloudcost.domain.waste.WasteFinding;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 비용 시뮬레이터 (이슈 #31, S08). 낭비 추천(#10)과 대시보드 월말 예상·예산(#27)을 받아 {@link CostSimulation}으로 계산한다.
 * 저장하지 않는다. 실제 연결과 체험 모드가 같은 {@link #compose}를 쓴다.
 */
public class SimulationService {

    public enum Status {
        /** 계산함 */
        OK,
        /** 월말 예상이 없어(끝난 날 기록 없음·단가를 아는 서버 없음) 계산하지 않음 */
        NO_FORECAST
    }

    /**
     * @param simulation NO_FORECAST면 null
     * @param budget     예산 (없으면 null)
     */
    public record SimulationView(
        UUID accountId,
        Status status,
        int trafficGrowthPercent,
        List<WasteFinding> selected,
        CostSimulation simulation,
        MonthlyBudget budget,
        Freshness freshness,
        List<String> notes,
        Instant asOf) {
    }

    private final WasteService waste;
    private final DashboardService dashboards;
    private final DemoUsageService demo;
    private final Clock clock;

    public SimulationService(WasteService waste, DashboardService dashboards, DemoUsageService demo, Clock clock) {
        this.waste = waste;
        this.dashboards = dashboards;
        this.demo = demo;
        this.clock = clock;
    }

    public SimulationView simulate(String ownerId, UUID accountId, List<String> recommendationIds, int growthPercent) {
        CostSimulation.requireValidGrowth(growthPercent);
        WasteView wasteView = waste.waste(ownerId, accountId);
        DashboardView dashboard = dashboards.dashboard(ownerId, accountId);
        return compose(accountId, wasteView, dashboard, recommendationIds, growthPercent, clock.instant());
    }

    public SimulationView demoSimulate(
        DemoSetup setup, MonthlyBudget budget, List<String> recommendationIds, int growthPercent) {
        CostSimulation.requireValidGrowth(growthPercent);
        return compose(DemoUsageService.ACCOUNT_ID, demo.waste(setup), dashboards.demoDashboard(setup, budget),
            recommendationIds, growthPercent, clock.instant());
    }

    static SimulationView compose(
        UUID accountId, WasteView wasteView, DashboardView dashboard, List<String> recommendationIds,
        int growthPercent, Instant asOf) {
        List<WasteFinding> selected = CostSimulation.select(wasteView.result().findings(), recommendationIds);
        BudgetStatus budget = dashboard.budget();
        List<String> notes = new ArrayList<>();
        notes.add(CostSimulation.METHOD);
        notes.add(WasteService.MONTHLY_BASIS);
        notes.add(WasteService.ADVICE_ONLY);
        if (dashboard.forecast() == null) {
            notes.add("월말 예상 비용이 아직 없어 시뮬레이션을 계산하지 않았어요. 끝난 날의 기록이 쌓이면 다시 시도해 주세요.");
            return new SimulationView(accountId, Status.NO_FORECAST, growthPercent, selected, null,
                budget == null ? null : budget.budget(), dashboard.freshness(), List.copyOf(notes), asOf);
        }
        if (budget != null && budget.budgetUsd() == null) {
            notes.add("환율을 가져오지 못해 원화 예산과 비교하지 않았어요.");
        }
        CostSimulation simulation = CostSimulation.of(dashboard.forecast().monthEndUsd(), selected, growthPercent,
            budget == null ? null : budget.budgetUsd());
        return new SimulationView(accountId, Status.OK, growthPercent, selected, simulation,
            budget == null ? null : budget.budget(), dashboard.freshness(), List.copyOf(notes), asOf);
    }
}
