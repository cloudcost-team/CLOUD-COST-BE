package com.cloudcost.entrypoint.api;

import com.cloudcost.application.CurrencyService;
import com.cloudcost.application.Ec2UsageQueryService;
import com.cloudcost.application.UsageSettings;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.api.dto.UsageDtos.CostSummaryResponse;
import com.cloudcost.entrypoint.api.dto.UsageDtos.DailyCostResponse;
import com.cloudcost.entrypoint.api.dto.UsageDtos.InstancesResponse;
import com.cloudcost.entrypoint.api.dto.UsageDtos.MonthlyCostResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/aws/accounts/{accountId}")
@Tag(name = "2. EC2 사용량·추정 비용",
    description = "조회할 때 캐시가 오래되었으면 AWS에서 다시 가져와 DB에 저장한다 (인스턴스 10분, CloudWatch 1시간). "
        + "그 안의 재조회는 DB 값으로 응답한다. 기간을 넣지 않아도 기본 기간으로 응답한다.")
class Ec2UsageController {

    private final Ec2UsageQueryService queries;
    private final CurrencyService currencies;
    private final String timezone;

    Ec2UsageController(Ec2UsageQueryService queries, CurrencyService currencies, UsageSettings settings) {
        this.queries = queries;
        this.currencies = currencies;
        this.timezone = settings.zone().getId();
    }

    @GetMapping("/instances")
    @Operation(
        summary = "EC2 인스턴스 목록 (상태 · 현재 실행 구간 · 시간당 단가)",
        description = "DescribeInstances 결과를 DB에 저장하고 보여 준다. 실행 중이면 LaunchTime부터의 현재 실행 구간 시간을 함께 준다.")
    InstancesResponse instances(
            @CurrentOwner String owner,
            @PathVariable UUID accountId,
            @Parameter(description = "금액 통화: KRW(기본, 원화 환산) 또는 USD")
                    @RequestParam(defaultValue = "KRW") Currency currency) {
        return InstancesResponse.of(queries.instances(owner, accountId), currencies.resolve(currency));
    }

    @GetMapping("/costs")
    @Operation(summary = "기간 합계: 사용 시간 · 추정 비용 (기본: 이번 달 1일 ~ 오늘)")
    CostSummaryResponse costs(
        @CurrentOwner String owner,
        @PathVariable UUID accountId,
        @Parameter(description = "시작일 (포함, Asia/Seoul 기준)", example = "2026-10-01")
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @Parameter(description = "종료일 (포함)", example = "2026-10-07")
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "금액 통화: KRW(기본, 원화 환산) 또는 USD")
                    @RequestParam(defaultValue = "KRW") Currency currency) {
        return CostSummaryResponse.of(queries.summary(owner, accountId, from, to), timezone, currencies.resolve(currency));
    }

    @GetMapping("/costs/daily")
    @Operation(summary = "일별 사용 시간 · 추정 비용 · 급증일 (기본: 최근 30일)",
        description = "days[].spike: 그날 비용이 직전 30일 중 기록 있는 날의 중앙값 × 1.5 이상이면 급증 (기록 7일 미만이면 판정하지 않음).")
    DailyCostResponse daily(
        @CurrentOwner String owner,
        @PathVariable UUID accountId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "금액 통화: KRW(기본, 원화 환산) 또는 USD")
                    @RequestParam(defaultValue = "KRW") Currency currency) {
        return DailyCostResponse.of(
            queries.dailyWithSpikes(owner, accountId, from, to), timezone, currencies.resolve(currency));
    }

    @GetMapping("/costs/monthly")
    @Operation(summary = "월별 사용 시간 · 추정 비용 = 일별 합산 (기본: 최근 3개월)")
    MonthlyCostResponse monthly(
        @CurrentOwner String owner,
        @PathVariable UUID accountId,
        @Parameter(description = "시작 월 (YYYY-MM)", example = "2026-08")
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth from,
        @Parameter(description = "종료 월 (YYYY-MM)", example = "2026-10")
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth to,
            @Parameter(description = "금액 통화: KRW(기본, 원화 환산) 또는 USD")
                    @RequestParam(defaultValue = "KRW") Currency currency) {
        return MonthlyCostResponse.of(queries.monthly(owner, accountId, from, to), timezone, currencies.resolve(currency));
    }
}
