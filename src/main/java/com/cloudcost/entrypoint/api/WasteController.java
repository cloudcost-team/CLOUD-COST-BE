package com.cloudcost.entrypoint.api;

import com.cloudcost.application.CurrencyService;
import com.cloudcost.application.WasteService;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.api.dto.UsageDtos;
import com.cloudcost.entrypoint.api.dto.WasteDtos.WasteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/aws/accounts/{accountId}")
@Tag(name = "4. 절감 (낭비 리소스)",
    description = "최근 14일 EC2 사용률(CPU·네트워크)로 과다 사양·유휴 서버를 찾고 매달 절감 예상액을 계산한다. 조언만 하고 AWS를 바꾸지 않는다.")
class WasteController {

    private final WasteService waste;
    private final CurrencyService currencies;

    WasteController(WasteService waste, CurrencyService currencies) {
        this.waste = waste;
        this.currencies = currencies;
    }

    @GetMapping("/waste")
    @Operation(
        summary = "낭비 리소스 추천 목록 (S07 · S07-2)",
        description = "과다 사양(CPU P95 10% 미만 → t3로 변경 검토)과 유휴(CPU 평균 1% 미만 + 네트워크 하루 5MB 미만 → 중지 검토). "
            + "항목마다 탐지 근거·사양 비교·주의사항·콘솔 변경 방법이 들어 있다. 기록이 14일 미만이면 status=INSUFFICIENT_HISTORY.")
    WasteResponse waste(
            @CurrentOwner String owner,
            @PathVariable UUID accountId,
            @Parameter(description = "금액 통화: KRW(기본, 원화 환산) 또는 USD")
                    @RequestParam(defaultValue = "KRW") Currency currency) {
        return WasteResponse.of(waste.waste(owner, accountId), currencies.resolve(currency), UsageDtos.DATA_SOURCE_AWS);
    }
}
