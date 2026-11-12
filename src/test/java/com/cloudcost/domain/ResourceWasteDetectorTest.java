package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.resource.ResourceFinding;
import com.cloudcost.domain.resource.ResourceScan;
import com.cloudcost.domain.resource.ResourceType;
import com.cloudcost.domain.resource.ResourceWasteDetector;
import com.cloudcost.domain.resource.ResourceWasteKind;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceWasteDetectorTest {

    static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    static final String HOME = "ap-northeast-2";

    static AwsResource r(ResourceType type, String id, String region, String subtype, Integer size, boolean inUse,
                         Instant created, Long requests) {
        return new AwsResource(type, id, null, region, subtype, size, inUse, created, requests, "Linux/UNIX");
    }

    static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    @Test
    void 다섯_가지_규칙으로_방치_리소스를_찾고_비싼_순으로_정렬한다() {
        List<AwsResource> list = List.of(
            r(ResourceType.VOLUME, "vol-free", HOME, "gp3", 100, false, daysAgo(10), null),
            r(ResourceType.VOLUME, "vol-used", HOME, "gp3", 100, true, daysAgo(10), null),
            r(ResourceType.ADDRESS, "eipalloc-free", HOME, null, null, false, null, null),
            r(ResourceType.ADDRESS, "eipalloc-used", HOME, null, null, true, null, null),
            r(ResourceType.SNAPSHOT, "snap-old", HOME, null, 50, true, daysAgo(91), null),
            r(ResourceType.SNAPSHOT, "snap-new", HOME, null, 50, true, daysAgo(30), null),
            r(ResourceType.LOAD_BALANCER, "lb-idle", HOME, "application", null, true, daysAgo(30), 0L),
            r(ResourceType.LOAD_BALANCER, "lb-busy", HOME, "application", null, true, daysAgo(30), 120L),
            r(ResourceType.LOAD_BALANCER, "lb-young", HOME, "application", null, true, daysAgo(3), 0L),
            r(ResourceType.LOAD_BALANCER, "lb-unknown", HOME, "gateway", null, true, daysAgo(30), null),
            r(ResourceType.INSTANCE, "i-far", "us-east-1", "t3.medium", null, true, daysAgo(100), null),
            r(ResourceType.INSTANCE, "i-far-stopped", "us-east-1", "t3.medium", null, false, daysAgo(100), null),
            r(ResourceType.INSTANCE, "i-home", HOME, "t3.medium", null, true, daysAgo(100), null));

        List<ResourceFinding> found = ResourceWasteDetector.detect(
            list, HOME, NOW, Map.of("i-far", new BigDecimal("0.0416")));

        assertThat(found).extracting(f -> f.resource().resourceId())
            .containsExactly("i-far", "lb-idle", "vol-free", "eipalloc-free", "snap-old");
        assertThat(found).extracting(ResourceFinding::kind).containsExactly(
            ResourceWasteKind.FORGOTTEN_REGION, ResourceWasteKind.IDLE_LB, ResourceWasteKind.UNATTACHED_VOLUME,
            ResourceWasteKind.UNUSED_EIP, ResourceWasteKind.OLD_SNAPSHOT);
        assertThat(found).extracting(ResourceFinding::monthlyCostUsd).containsExactly(
            new BigDecimal("30.37"),   // 0.0416 × 730
            new BigDecimal("16.43"),   // 0.0225 × 730
            new BigDecimal("9.12"),    // 서울 gp3 0.0912 × 100
            new BigDecimal("3.65"),    // 0.005 × 730
            new BigDecimal("2.50"));   // 0.05 × 50
        assertThat(found.getFirst().consoleSteps().getFirst()).contains("us-east-1");
    }

    @Test
    void 단가를_모르는_리전은_버지니아_값으로_추정하고_그렇게_적는다() {
        List<ResourceFinding> found = ResourceWasteDetector.detect(
            List.of(r(ResourceType.VOLUME, "vol-x", "eu-west-1", "gp3", 10, false, daysAgo(1), null)),
            HOME, NOW, Map.of());

        assertThat(found.getFirst().monthlyCostUsd()).isEqualByComparingTo("0.80");
        assertThat(found.getFirst().priceNote()).contains("us-east-1");
    }

    @Test
    void 확장_수집은_성공이면_6시간_권한_부족이면_1시간마다_다시_한다() {
        ResourceScan ok = new ResourceScan(ResourceScan.Status.OK, 2, NOW, null);
        assertThat(ok.stale(NOW.plus(Duration.ofHours(5)))).isFalse();
        assertThat(ok.stale(NOW.plus(Duration.ofHours(6)))).isTrue();

        ResourceScan denied = new ResourceScan(ResourceScan.Status.PERMISSION_UPGRADE_REQUIRED, 1, NOW, "x");
        assertThat(denied.stale(NOW.plus(Duration.ofMinutes(59)))).isFalse();
        assertThat(denied.stale(NOW.plus(Duration.ofHours(1)))).isTrue();
        assertThat(ResourceScan.notScanned().stale(NOW)).isTrue();
    }
}
