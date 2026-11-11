package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.CurrencyService.Resolved;
import com.cloudcost.application.ResourceWasteService.ResourceWasteView;
import com.cloudcost.application.ResourceWasteService.Upgrade;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.resource.ResourceFinding;
import com.cloudcost.domain.resource.ResourceScan;
import com.cloudcost.domain.resource.ResourceType;
import com.cloudcost.entrypoint.api.dto.UsageDtos.ExchangeRateDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 방치 리소스·잊힌 리전 (ADR-020) */
public final class ResourceDtos {

    private ResourceDtos() {}

    public static final String COST_BASIS = "estimated_list_price";
    public static final String COST_NOTE =
            "볼륨·고정 IP·스냅샷·로드밸런서 금액은 AWS 공개 정가로 계산한 월 추정치예요 (실제 청구와 다를 수 있어요).";
    public static final String READ_ONLY_NOTE =
            "CloudCost는 읽기만 해요. 정리는 AWS 콘솔에서 직접 해 주세요 (Describe 호출은 고객 CloudTrail에 기록돼요).";

    @Schema(description = "확장 수집 상태")
    public record ScanDto(
            @Schema(description = "NOT_SCANNED | OK | PERMISSION_UPGRADE_REQUIRED | FAILED") String status,
            @Schema(description = "1 = 이전 템플릿(EC2·CloudWatch만), 2 = 확장 권한까지 확인됨") int permissionVersion,
            Instant scannedAt,
            @Schema(description = "실패·권한 부족 사유 (한국어)") String message) {

        static ScanDto of(ResourceScan s) {
            return new ScanDto(s.status().name(), s.permissionVersion(), s.scannedAt(), s.message());
        }
    }

    @Schema(description = "권한 업데이트 방법 (PERMISSION_UPGRADE_REQUIRED일 때만)")
    public record UpgradeDto(String stackName, String consoleUrl, String templateUrl, List<String> steps) {

        static UpgradeDto of(Upgrade u) {
            return u == null ? null : new UpgradeDto(u.stackName(), u.consoleUrl(), u.templateUrl(), u.steps());
        }
    }

    public record ResourceItemDto(
            @Schema(example = "ap-northeast-2:vol-0abc:UNATTACHED_VOLUME") String recommendationId,
            @Schema(description = "UNATTACHED_VOLUME | UNUSED_EIP | OLD_SNAPSHOT | IDLE_LB | FORGOTTEN_REGION") String kind,
            @Schema(example = "연결 안 된 볼륨") String kindLabel,
            @Schema(description = "INSTANCE | VOLUME | ADDRESS | SNAPSHOT | LOAD_BALANCER") String resourceType,
            @Schema(example = "볼륨") String resourceTypeLabel,
            @Schema(example = "vol-0abc") String resourceId,
            @Schema(description = "Name 태그, 없으면 resourceId") String name,
            @Schema(example = "ap-northeast-2") String region,
            @Schema(description = "기본 리전이 아니면 true") boolean otherRegion,
            @Schema(description = "월 추정 비용 (통화는 currency). 모르면 null") BigDecimal monthlyCost,
            String evidence,
            String action,
            List<String> consoleSteps,
            List<String> cautions,
            @Schema(description = "금액 계산 안내 (없으면 null)") String priceNote) {

        static ResourceItemDto of(ResourceFinding f, String homeRegion, DisplayCurrency d) {
            ResourceType type = f.resource().type();
            return new ResourceItemDto(f.recommendationId(), f.kind().name(), f.kind().label(), type.name(),
                    type.label(), f.resource().resourceId(), f.displayName(), f.resource().region(),
                    !homeRegion.equals(f.resource().region()),
                    f.monthlyCostUsd() == null ? null : d.cost(f.monthlyCostUsd()), f.evidence(), f.action(),
                    f.consoleSteps(), f.cautions(), f.priceNote());
        }
    }

    public record RegionCountDto(String region, int runningServers) {}

    @Schema(description = "방치 리소스 (볼륨·고정 IP·스냅샷·로드밸런서·잊힌 리전 서버). 금액은 추정")
    public record ResourceWasteDto(
            String costBasis,
            @Schema(example = "KRW") String currency,
            String homeRegion,
            ScanDto scan,
            UpgradeDto upgrade,
            int findingCount,
            @Schema(description = "모두 정리했을 때 매달 줄어드는 추정 금액") BigDecimal totalMonthlyCost,
            @Schema(description = "기본 리전 밖에서 실행 중인 서버 (대시보드 '잊힌 리전' 카드)") List<RegionCountDto> otherRegions,
            List<ResourceItemDto> items,
            List<String> notes) {

        public static ResourceWasteDto of(ResourceWasteView v, DisplayCurrency d) {
            List<RegionCountDto> regions = new ArrayList<>();
            for (Map.Entry<String, Integer> e : v.otherRegionServers().entrySet()) {
                regions.add(new RegionCountDto(e.getKey(), e.getValue()));
            }
            List<String> notes = new ArrayList<>(List.of(COST_NOTE, READ_ONLY_NOTE));
            if (v.scan().status() == ResourceScan.Status.PERMISSION_UPGRADE_REQUIRED) {
                notes.addFirst("권한 업데이트 필요: 이전 방식으로 연결해 EC2 사용량만 볼 수 있어요. 스택을 업데이트하면 방치 리소스도 찾아 드려요.");
            }
            return new ResourceWasteDto(COST_BASIS, d.currency().name(), v.homeRegion(), ScanDto.of(v.scan()),
                    UpgradeDto.of(v.upgrade()), v.findings().size(), d.cost(v.totalMonthlyUsd()), List.copyOf(regions),
                    v.findings().stream().map(f -> ResourceItemDto.of(f, v.homeRegion(), d)).toList(),
                    List.copyOf(notes));
        }
    }

    /** GET /aws/accounts/{id}/resources/waste 응답 */
    public record ResourceWasteResponse(
            String dataSource,
            ExchangeRateDto exchangeRate,
            java.util.UUID accountId,
            ResourceWasteDto resources) {

        public static ResourceWasteResponse of(ResourceWasteView v, Resolved currency, String dataSource) {
            DisplayCurrency d = currency.display();
            ResourceWasteDto dto = ResourceWasteDto.of(v, d);
            if (!currency.warnings().isEmpty() || d.conversionNote() != null) {
                List<String> notes = new ArrayList<>(dto.notes());
                if (d.conversionNote() != null) {
                    notes.add(d.conversionNote());
                }
                notes.addAll(currency.warnings());
                dto = new ResourceWasteDto(dto.costBasis(), dto.currency(), dto.homeRegion(), dto.scan(), dto.upgrade(),
                        dto.findingCount(), dto.totalMonthlyCost(), dto.otherRegions(), dto.items(), List.copyOf(notes));
            }
            return new ResourceWasteResponse(dataSource, ExchangeRateDto.of(d), v.accountId(), dto);
        }
    }
}
