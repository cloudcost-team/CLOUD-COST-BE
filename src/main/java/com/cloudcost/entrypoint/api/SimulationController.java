package com.cloudcost.entrypoint.api;

import com.cloudcost.application.CurrencyService;
import com.cloudcost.application.SimulationService;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.api.dto.SimulationDtos.SimulationRequest;
import com.cloudcost.entrypoint.api.dto.SimulationDtos.SimulationResponse;
import com.cloudcost.entrypoint.api.dto.UsageDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/aws/accounts/{accountId}")
@Tag(name = "4-1. 비용 시뮬레이터",
    description = "낭비 추천을 적용하고 트래픽이 늘었을 때 월 비용이 어떻게 바뀌는지 계산한다 (S08). 저장하지 않는다.")
class SimulationController {

    private final SimulationService simulations;
    private final CurrencyService currencies;

    SimulationController(SimulationService simulations, CurrencyService currencies) {
        this.simulations = simulations;
        this.currencies = currencies;
    }

    @PostMapping("/simulations")
    @Operation(summary = "비용 시뮬레이션 (S08 · S08-2)",
        description = "적용 전 = 월말 예상 × (1+증가율), 적용 후 = (월말 예상 − 고른 추천 절감액) × (1+증가율). "
            + "모르는 추천 id는 400 UNKNOWN_RECOMMENDATION.")
    SimulationResponse simulate(
            @CurrentOwner String owner,
            @PathVariable UUID accountId,
            @Valid @RequestBody SimulationRequest request,
            @Parameter(description = "금액 통화: KRW(기본, 원화 환산) 또는 USD")
                    @RequestParam(defaultValue = "KRW") Currency currency) {
        return SimulationResponse.of(simulations.simulate(owner, accountId, request.ids(), request.growth()),
            currencies.resolve(currency), UsageDtos.DATA_SOURCE_AWS);
    }
}
