package com.cloudcost.entrypoint.config;

import com.cloudcost.entrypoint.api.auth.OwnerArgumentResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    private static final String DESCRIPTION = """
            AWS 계정의 EC2 인스턴스·사용 시간·**추정 비용**을 조회하는 API입니다.

            **로그인**
            - [/login.html](/login.html)에서 Google로 로그인 → 화면의 액세스 토큰 복사
            - 오른쪽 위 **Authorize** → `bearerAuth`에 토큰 붙여 넣기 (토큰 유효 1시간)
            - 로컬(AUTH_MODE=dev)에서는 토큰 없이 `devOwner`(X-Owner-Id)로도 테스트할 수 있습니다

            **AWS 연결**
            1. `POST /api/v1/aws/accounts` → `roleSetup.quickCreateUrl`을 열어 AWS에서 스택 생성 (체크 1개)
            2. 1~2분 뒤 `GET /api/v1/aws/accounts/{accountId}`의 status가 ACTIVE
               (예비: Role을 직접 만들고 `PUT .../role`로 ARN 등록)
            3. `GET .../instances`, `GET .../costs`, `GET .../costs/daily`, `GET .../costs/monthly`

            **조회 시점 수집 (캐시)**
            - 인스턴스 정보는 10분, CloudWatch 사용량은 **1시간에 1회**만 AWS에서 다시 가져옵니다.
            - 그 안에 다시 조회하면 DB에 저장된 값으로 응답합니다 (`freshness.usageFromCache=true`).

            **비용은 실제 청구 금액이 아닙니다.** 사용 시간 × 온디맨드 시간당 단가로 계산한 추정치(`estimated_on_demand`)이며
            RI·Savings Plans·Spot 할인, EBS, 데이터 전송 비용은 포함하지 않습니다.
            """;

    @Bean
    OpenAPI cloudCostOpenApi() {
        return new OpenAPI()
                .info(new Info().title("CloudCost Insight API").version("v1").description(DESCRIPTION))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("/login.html에서 Google 로그인 후 받은 액세스 토큰"))
                        .addSecuritySchemes("devOwner", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(OwnerArgumentResolver.HEADER)
                                .description("로컬 개발 전용(AUTH_MODE=dev). 토큰이 없을 때만 쓰인다. 다른 값을 넣으면 다른 사용자로 동작한다.")))
                // 둘 중 하나 (OR)
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addSecurityItem(new SecurityRequirement().addList("devOwner"));
    }
}
