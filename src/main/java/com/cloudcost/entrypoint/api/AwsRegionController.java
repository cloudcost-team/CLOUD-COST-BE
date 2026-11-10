package com.cloudcost.entrypoint.api;

import com.cloudcost.domain.account.AwsRegion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "1. AWS 연결")
class AwsRegionController {

    record RegionResponse(
            @Schema(description = "리전 코드. POST /aws/accounts의 region에 그대로 보낸다") String code,
            @Schema(description = "한국어 이름 (예: 서울)") String label,
            @Schema(description = "기본 선택 리전인지") boolean isDefault) {}

    record RegionsResponse(List<RegionResponse> regions) {}

    @GetMapping("/api/v1/aws/regions")
    @Operation(
            summary = "연결할 때 고를 수 있는 리전 목록",
            description = "화면 순서대로 돌려준다(첫 번째가 기본 ap-northeast-2). AWS를 호출하지 않는다. "
                    + "계정마다 따로 켜야 하는 옵트인 리전은 넣지 않는다.")
    RegionsResponse regions() {
        return new RegionsResponse(AwsRegion.selectable().stream()
                .map(r -> new RegionResponse(r.value(), r.koreanLabel(), r.value().equals(AwsRegion.DEFAULT)))
                .toList());
    }
}
