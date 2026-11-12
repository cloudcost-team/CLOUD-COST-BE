package com.cloudcost.domain.demo;

import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.resource.ResourceType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 체험 모드의 방치 리소스 예시 (ADR-008·020). 실제 계정이 아니다. 시각은 요청 시각 기준으로 만든다. */
public final class DemoResources {

    public static final String HOME_REGION = "ap-northeast-2";
    /** 잊힌 리전 예시 서버(t3.medium, us-east-1)의 시간당 단가 */
    public static final Map<String, BigDecimal> HOURLY_USD = Map.of("i-0demo0forgotten01", new BigDecimal("0.0416"));

    private DemoResources() {}

    public static List<AwsResource> resources(Instant now) {
        return List.of(
            new AwsResource(ResourceType.VOLUME, "vol-0demo0unattached1", "old-db-data", HOME_REGION, "gp3", 100, false,
                now.minus(Duration.ofDays(45)), null, null),
            new AwsResource(ResourceType.VOLUME, "vol-0demo0attached001", "web-root", HOME_REGION, "gp3", 30, true,
                now.minus(Duration.ofDays(200)), null, null),
            new AwsResource(ResourceType.ADDRESS, "eipalloc-0demo0unused01", "staging-ip", HOME_REGION, null, null,
                false, null, null, null),
            new AwsResource(ResourceType.SNAPSHOT, "snap-0demo0old000001", "before-migration", HOME_REGION, null, 50,
                true, now.minus(Duration.ofDays(210)), null, null),
            new AwsResource(ResourceType.LOAD_BALANCER, "demo-old-alb", "demo-old-alb", HOME_REGION, "application",
                null, true, now.minus(Duration.ofDays(60)), 0L, null),
            new AwsResource(ResourceType.INSTANCE, "i-0demo0forgotten01", "test-server-virginia", "us-east-1",
                "t3.medium", null, true, now.minus(Duration.ofDays(120)), null, "Linux/UNIX"));
    }
}
