package com.cloudcost.entrypoint.api;

import com.cloudcost.application.CurrencyService;
import com.cloudcost.application.DashboardService;
import com.cloudcost.application.UsageSettings;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.api.dto.DashboardDtos.BudgetRequest;
import com.cloudcost.entrypoint.api.dto.DashboardDtos.BudgetResponse;
import com.cloudcost.entrypoint.api.dto.DashboardDtos.DashboardResponse;
import com.cloudcost.entrypoint.api.dto.UsageDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/aws/accounts/{accountId}")
@Tag(name = "2-1. 대시보드 요약 · 월 예산",
    description = "S06 상단 카드: 이번 달 추정 비용, 사용 시간(실행 중·중지 대수), 월말 예상 비용과 예산 대비, 전월 같은 기간 대비. "
        + "월 예산은 연결마다 정한다 (S14).")
class DashboardController {

    private final DashboardService dashboards;
    private final CurrencyService currencies;
    private final String timezone;

    DashboardController(DashboardService dashboards, CurrencyService currencies, UsageSettings settings) {
        this.dashboards = dashboards;
        this.currencies = currencies;
        this.timezone = settings.zone().getId();
    }

    @GetMapping("/dashboard")
    @Operation(summary = "대시보드 요약 (S06 상단 카드 4개)",
        description = "월말 예상 = 어제까지 이번 달 비용 + 최근 7일 하루 평균 × 오늘부터 남은 날. "
            + "전월 대비는 이번 달 1일~어제와 전월 1일~같은 날을 비교한다.")
    DashboardResponse dashboard(
            @CurrentOwner String owner,
            @PathVariable UUID accountId,
            @Parameter(description = "금액 통화: KRW(기본, 원화 환산) 또는 USD")
                    @RequestParam(defaultValue = "KRW") Currency currency) {
        return DashboardResponse.of(dashboards.dashboard(owner, accountId), timezone, currencies.resolve(currency),
            UsageDtos.DATA_SOURCE_AWS);
    }

    @PutMapping("/budget")
    @Operation(summary = "월 예산 정하기 · 바꾸기",
        description = "입력한 통화 그대로 저장한다 (원화는 원 단위, 달러는 센트 단위). 0 이하·1조 초과는 400 VALIDATION_ERROR.")
    BudgetResponse setBudget(
            @CurrentOwner String owner, @PathVariable UUID accountId, @Valid @RequestBody BudgetRequest request) {
        return BudgetResponse.of(accountId, dashboards.setBudget(owner, accountId, request.toDomain()));
    }

    @DeleteMapping("/budget")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "월 예산 지우기 (설정 안 함)")
    void deleteBudget(@CurrentOwner String owner, @PathVariable UUID accountId) {
        dashboards.deleteBudget(owner, accountId);
    }
}
