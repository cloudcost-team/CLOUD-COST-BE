package com.cloudcost.entrypoint.api;

import com.cloudcost.application.CurrencyService;
import com.cloudcost.application.DashboardService;
import com.cloudcost.application.ResourceWasteService;
import com.cloudcost.application.WasteService;
import com.cloudcost.entrypoint.api.dto.ResourceDtos;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.domain.waste.WasteKind;
import com.cloudcost.domain.waste.WasteSort;
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
    private final ResourceWasteService resources;
    private final DashboardService dashboards;
    private final CurrencyService currencies;

    WasteController(
            WasteService waste, ResourceWasteService resources, DashboardService dashboards, CurrencyService currencies) {
        this.waste = waste;
        this.resources = resources;
        this.dashboards = dashboards;
        this.currencies = currencies;
    }

    @GetMapping("/waste")
    @Operation(
        summary = "낭비 리소스 추천 목록 (S07 · S07-2)",
        description = "과다 사양(CPU 평균 10% 미만 + P95 40% 미만 → 기본 성능 안에 드는 가장 싼 t3로 변경 검토)과 "
            + "유휴(CPU 최대 5% 미만 + 네트워크 하루 5MB 미만 → 중지 검토). "
            + "항목마다 탐지 근거·사양 비교·주의사항·콘솔 변경 방법이 들어 있다. 기록이 14일 미만이면 status=INSUFFICIENT_HISTORY.")
    WasteResponse waste(
            @CurrentOwner String owner,
            @PathVariable UUID accountId,
            @Parameter(description = "금액 통화: KRW(기본, 원화 환산) 또는 USD")
                    @RequestParam(defaultValue = "KRW") Currency currency,
            @Parameter(description = "정렬: SAVINGS(기본) · COST · UTILIZATION · NAME")
                    @RequestParam(defaultValue = "SAVINGS") WasteSort sort,
            @Parameter(description = "종류 필터: OVERSIZED · IDLE (없으면 모두)") @RequestParam(required = false) WasteKind type) {
        WasteService.WasteView view = waste.waste(owner, accountId);
        CurrencyService.Resolved resolved = currencies.resolve(currency);
        return WasteResponse.of(view, dashboards.dashboard(owner, accountId).forecast(), resolved,
            UsageDtos.DATA_SOURCE_AWS, sort, type)
            .withResources(ResourceDtos.ResourceWasteDto.of(resources.view(owner, accountId), resolved.display()));
    }

    @GetMapping("/resources/waste")
    @Operation(
        summary = "방치 리소스·잊힌 리전 (ADR-020)",
        description = "연결 안 된 볼륨, 사용하지 않는 고정 IP, 90일 넘은 스냅샷, 14일 요청 없는 로드밸런서, 기본 리전 밖에서 실행 중인 서버. "
            + "모든 리전을 6시간에 한 번 Describe로 훑는다. 이전 템플릿으로 연결했으면 오류 대신 "
            + "scan.status=PERMISSION_UPGRADE_REQUIRED와 스택 업데이트 방법(upgrade)을 준다. 대시보드 '잊힌 리전' 카드는 otherRegions를 쓴다.")
    ResourceDtos.ResourceWasteResponse resourceWaste(
            @CurrentOwner String owner,
            @PathVariable UUID accountId,
            @Parameter(description = "금액 통화: KRW(기본) 또는 USD") @RequestParam(defaultValue = "KRW") Currency currency) {
        return ResourceDtos.ResourceWasteResponse.of(
            resources.view(owner, accountId), currencies.resolve(currency), UsageDtos.DATA_SOURCE_AWS);
    }
}
