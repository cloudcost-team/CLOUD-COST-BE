package com.cloudcost.entrypoint.api;

import com.cloudcost.application.CurrencyService;
import com.cloudcost.application.ReportService;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.api.dto.ReportDtos.ReportDetailResponse;
import com.cloudcost.entrypoint.api.dto.ReportDtos.ReportListResponse;
import com.cloudcost.entrypoint.api.dto.UsageDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "6. 월간 리포트",
    description = "달마다 추정 비용·전월 대비·급증일·절감 제안·서버별 비중을 보여 준다 (S10). 저장하지 않고 조회할 때 계산한다.")
class ReportController {

    private final ReportService reports;
    private final CurrencyService currencies;

    ReportController(ReportService reports, CurrencyService currencies) {
        this.reports = reports;
        this.currencies = currencies;
    }

    @GetMapping("/reports")
    @Operation(summary = "월간 리포트 목록 (S10-2)", description = "기록 시작 달 ~ 이번 달 (최대 14개월), 최신 달 먼저.")
    ReportListResponse list(
            @CurrentOwner String owner,
            @PathVariable UUID accountId,
            @Parameter(description = "금액 통화: KRW(기본) 또는 USD") @RequestParam(defaultValue = "KRW") Currency currency) {
        return ReportListResponse.of(reports.list(owner, accountId), currencies.resolve(currency),
            UsageDtos.DATA_SOURCE_AWS);
    }

    @GetMapping("/reports/{month}")
    @Operation(summary = "월간 리포트 상세 (S10 · S10-4)",
        description = "status: FINAL · PREVIEW · FIRST_MONTH · EMPTY. 아직 오지 않은 달은 400 INVALID_PERIOD.")
    ReportDetailResponse detail(
            @CurrentOwner String owner,
            @PathVariable UUID accountId,
            @Parameter(description = "yyyy-MM", example = "2026-10")
                    @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(defaultValue = "KRW") Currency currency) {
        return ReportDetailResponse.of(reports.detail(owner, accountId, month), currencies.resolve(currency),
            UsageDtos.DATA_SOURCE_AWS);
    }
}
