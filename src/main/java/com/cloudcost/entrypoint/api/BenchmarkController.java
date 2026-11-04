package com.cloudcost.entrypoint.api;

import com.cloudcost.application.BenchmarkService;
import com.cloudcost.application.CurrencyService;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.api.dto.BenchmarkDtos.BenchmarkResponse;
import com.cloudcost.entrypoint.api.dto.UsageDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/aws/accounts/{accountId}")
@Tag(name = "4-2. 팀 벤치마킹",
    description = "과다 사양 서버 비율·월 지출을 비슷한 규모 팀의 공개 자료와 견준다 (S09). 비교 기준은 교체 가능한 파일이다 (ADR-013).")
class BenchmarkController {

    private final BenchmarkService benchmarks;
    private final CurrencyService currencies;

    BenchmarkController(BenchmarkService benchmarks, CurrencyService currencies) {
        this.benchmarks = benchmarks;
        this.currencies = currencies;
    }

    @GetMapping("/benchmark")
    @Operation(summary = "팀 벤치마킹 (S09 · S09-2~4)",
        description = "① 과다 사양 비율(CPU P95 10% 미만) ② 전체 낭비 비율(UNAVAILABLE, 참고값만) ③ 월 지출 위치. "
            + "recommendationIds를 주면 시뮬레이터와 같은 계산으로 적용 후 위치(afterSimulation)를 함께 준다.")
    BenchmarkResponse benchmark(
            @CurrentOwner String owner,
            @PathVariable UUID accountId,
            @Parameter(description = "적용할 추천 id (쉼표로 구분하거나 여러 번)", example = "i-0abc:OVERSIZED,i-0def:IDLE")
                    @RequestParam(required = false) List<String> recommendationIds,
            @Parameter(description = "비교할 서버 대수 구간 (예: 1-2, 3-20). 없으면 우리 서버 대수에 맞는 구간. "
                    + "고를 수 있는 값은 응답 oversizedRatio.sizeBands", example = "1-2")
                    @RequestParam(required = false) String sizeBand,
            @Parameter(description = "금액 통화: KRW(기본, 원화 환산) 또는 USD")
                    @RequestParam(defaultValue = "KRW") Currency currency) {
        return BenchmarkResponse.of(benchmarks.benchmark(owner, accountId, recommendationIds, sizeBand),
            currencies.resolve(currency), UsageDtos.DATA_SOURCE_AWS);
    }
}
