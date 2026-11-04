package com.cloudcost.entrypoint.api;

import com.cloudcost.application.CurrencyService;
import com.cloudcost.application.DemoUsageService;
import com.cloudcost.application.UsageSettings;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.entrypoint.api.dto.DemoDtos.CatalogResponse;
import com.cloudcost.entrypoint.api.dto.DemoDtos.DemoAccountResponse;
import com.cloudcost.entrypoint.api.dto.DemoDtos.LoginRequest;
import com.cloudcost.entrypoint.api.dto.DemoDtos.SetupRequest;
import com.cloudcost.entrypoint.api.dto.UsageDtos;
import com.cloudcost.entrypoint.api.dto.UsageDtos.CostSummaryResponse;
import com.cloudcost.entrypoint.api.dto.UsageDtos.DailyCostResponse;
import com.cloudcost.entrypoint.api.dto.UsageDtos.InstancesResponse;
import com.cloudcost.entrypoint.api.dto.UsageDtos.MonthlyCostResponse;
import com.cloudcost.entrypoint.api.dto.WasteDtos.WasteResponse;
import com.cloudcost.entrypoint.api.dto.DashboardDtos.DashboardResponse;
import com.cloudcost.application.DashboardService;
import com.cloudcost.application.BenchmarkService;
import com.cloudcost.application.SimulationService;
import com.cloudcost.entrypoint.api.dto.BenchmarkDtos.BenchmarkResponse;
import com.cloudcost.entrypoint.api.dto.SimulationDtos.SimulationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
@Tag(name = "3. 체험 모드 (실제 계정 없이)",
    description = "간이 로그인(cloudcost / 1234) 후, 서버 구성과 기간을 정하면 실제 연결 계정과 같은 형식으로 비용을 계산한다. "
        + "Google 로그인이 필요 없고 AWS·DB를 부르지 않는다. 응답의 dataSource는 \"demo\"다.")
class DemoController {

    private final DemoUsageService demo;
    private final DashboardService dashboards;
    private final SimulationService simulations;
    private final BenchmarkService benchmarks;
    private final CurrencyService currencies;
    private final String timezone;

    DemoController(
            DemoUsageService demo,
            DashboardService dashboards,
            SimulationService simulations,
            BenchmarkService benchmarks,
            CurrencyService currencies,
            UsageSettings settings) {
        this.demo = demo;
        this.dashboards = dashboards;
        this.simulations = simulations;
        this.benchmarks = benchmarks;
        this.currencies = currencies;
        this.timezone = settings.zone().getId();
    }

    @PostMapping("/login")
    @Operation(summary = "체험용 간이 로그인", description = "실제 AWS 로그인이 아니다. 아이디·비밀번호가 맞으면 체험용 계정 정보를 준다.")
    DemoAccountResponse login(@Valid @RequestBody LoginRequest request) {
        return DemoAccountResponse.of(demo.login(request.username(), request.password()));
    }

    @GetMapping("/catalog")
    @Operation(summary = "고를 수 있는 인스턴스 타입·단가·사용 패턴")
    CatalogResponse catalog() {
        return CatalogResponse.of(DemoFleet.PRICE_NOTE);
    }

    @PostMapping("/instances")
    @Operation(summary = "설정한 구성의 EC2 인스턴스 목록 (실제: GET /aws/accounts/{id}/instances)")
    InstancesResponse instances(
            @Valid @RequestBody SetupRequest setup,
            @Parameter(description = "금액 통화: KRW(기본, 원화 환산) 또는 USD")
                    @RequestParam(defaultValue = "KRW") Currency currency) {
        return InstancesResponse.of(
                demo.instances(setup.toDomain()), currencies.resolve(currency), UsageDtos.DATA_SOURCE_DEMO);
    }

    @PostMapping("/costs")
    @Operation(summary = "설정한 기간 합계 (실제: GET /aws/accounts/{id}/costs)")
    CostSummaryResponse costs(
            @Valid @RequestBody SetupRequest setup, @RequestParam(defaultValue = "KRW") Currency currency) {
        return CostSummaryResponse.of(
                demo.summary(setup.toDomain()), timezone, currencies.resolve(currency), UsageDtos.DATA_SOURCE_DEMO);
    }

    @PostMapping("/costs/daily")
    @Operation(summary = "설정한 기간의 일별 (실제: GET /aws/accounts/{id}/costs/daily)")
    DailyCostResponse daily(
            @Valid @RequestBody SetupRequest setup, @RequestParam(defaultValue = "KRW") Currency currency) {
        return DailyCostResponse.of(
                demo.dailyWithSpikes(setup.toDomain()), timezone, currencies.resolve(currency),
                UsageDtos.DATA_SOURCE_DEMO);
    }

    @PostMapping("/costs/monthly")
    @Operation(summary = "설정한 기간의 월별 (실제: GET /aws/accounts/{id}/costs/monthly)")
    MonthlyCostResponse monthly(
            @Valid @RequestBody SetupRequest setup, @RequestParam(defaultValue = "KRW") Currency currency) {
        return MonthlyCostResponse.of(
                demo.monthly(setup.toDomain()), timezone, currencies.resolve(currency), UsageDtos.DATA_SOURCE_DEMO);
    }

    @PostMapping("/waste")
    @Operation(summary = "설정한 구성의 낭비 리소스 (실제: GET /aws/accounts/{id}/waste)",
            description = "기간의 마지막 14일을 서버별 usagePattern으로 판단한다. 기간이 14일보다 짧으면 INSUFFICIENT_HISTORY.")
    WasteResponse waste(
            @Valid @RequestBody SetupRequest setup, @RequestParam(defaultValue = "KRW") Currency currency) {
        var domain = setup.toDomain();
        return WasteResponse.of(demo.waste(domain), dashboards.demoDashboard(domain, null).forecast(),
                currencies.resolve(currency), UsageDtos.DATA_SOURCE_DEMO);
    }

    @PostMapping("/dashboard")
    @Operation(summary = "설정한 구성의 대시보드 요약 (실제: GET /aws/accounts/{id}/dashboard)",
            description = "기간의 마지막 날(to)을 오늘로 본다. 요청에 budget을 넣으면 예산 대비를 함께 계산한다.")
    DashboardResponse dashboard(
            @Valid @RequestBody SetupRequest setup, @RequestParam(defaultValue = "KRW") Currency currency) {
        return DashboardResponse.of(dashboards.demoDashboard(setup.toDomain(), setup.budgetOrNull()), timezone,
                currencies.resolve(currency), UsageDtos.DATA_SOURCE_DEMO);
    }

    @PostMapping("/simulations")
    @Operation(summary = "설정한 구성의 비용 시뮬레이션 (실제: POST /aws/accounts/{id}/simulations)",
            description = "요청에 recommendationIds·trafficGrowthPercent·budget(선택)을 더한다. 추천은 POST /demo/waste와 같은 설정에서 나온 id다.")
    SimulationResponse simulations(
            @Valid @RequestBody SetupRequest setup, @RequestParam(defaultValue = "KRW") Currency currency) {
        return SimulationResponse.of(
                simulations.demoSimulate(setup.toDomain(), setup.budgetOrNull(), setup.ids(), setup.growth()),
                currencies.resolve(currency), UsageDtos.DATA_SOURCE_DEMO);
    }

    @PostMapping("/benchmark")
    @Operation(summary = "설정한 구성의 팀 벤치마킹 (실제: GET /aws/accounts/{id}/benchmark)",
            description = "기간의 마지막 14일을 서버별 usagePattern으로 판단한다. recommendationIds(선택)를 주면 적용 후 위치도 준다.")
    BenchmarkResponse benchmark(
            @Valid @RequestBody SetupRequest setup, @RequestParam(defaultValue = "KRW") Currency currency) {
        return BenchmarkResponse.of(benchmarks.demoBenchmark(setup.toDomain(), setup.ids()),
                currencies.resolve(currency), UsageDtos.DATA_SOURCE_DEMO);
    }
}
