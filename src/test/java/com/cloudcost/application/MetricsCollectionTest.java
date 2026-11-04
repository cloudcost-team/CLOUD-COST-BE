package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.application.port.Ec2InstanceRepository;
import com.cloudcost.application.port.Ec2UsageRepository;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.usage.HourlySample;
import com.cloudcost.domain.usage.UtilizationStats;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 낭비 탐지용 지표 수집 (이슈 #24, ADR-009): CPU 평균·최대는 사용 시간 조회에서 함께 받고,
 * 네트워크 입출력은 최근 14일만 따로 받는다. NOW = 2026-10-08T03:00Z → 14일 전 = 2026-09-24T03:00Z.
 */
class MetricsCollectionTest extends IntegrationTestBase {

    private static final Instant FOURTEEN_DAYS_AGO = Instant.parse("2026-09-24T03:00:00Z");

    @Autowired Ec2UsageRepository usageRepository;
    @Autowired Ec2InstanceRepository instanceRepository;

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addCpuHours("i-run", Instant.parse("2026-09-01T00:00:00Z"), 24, 30, 80);   // 14일보다 앞
        aws.addCpuHours("i-run", Instant.parse("2026-10-07T00:00:00Z"), 10, 6, 23);    // 최근 14일 안
        aws.addNetworkHours("i-run", Instant.parse("2026-10-07T00:00:00Z"), 10, 1_000, 2_000);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
    }

    private JsonNode summary() throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/costs?currency=USD&from=2026-07-10&to=2026-10-08")
                .andExpect(status().isOk()));
    }

    private List<HourlySample> stored(Instant from) {
        return usageRepository.findHourly(owner, UUID.fromString(accountId), from, NOW.plus(Duration.ofDays(1)));
    }

    private TrackedInstance tracked() {
        return instanceRepository.findAllByOwnerIdAndAccountId(owner, UUID.fromString(accountId)).getFirst();
    }

    @Test
    void 처음_수집하면_CPU_평균_최대와_최근_14일_네트워크를_저장한다() throws Exception {
        JsonNode body = summary();

        assertThat(body.at("/usageHours").decimalValue()).as("사용 시간 계산은 그대로").isEqualByComparingTo("34.00");
        assertThat(aws.cloudWatchCalls).as("CPU는 소급 3구간 그대로 (추가 호출 없음)").isEqualTo(3);
        assertThat(aws.networkRequests).as("네트워크는 최근 14일 1구간 × 입출력")
                .containsExactly(
                        "IN:i-run@" + FOURTEEN_DAYS_AGO + "~" + NOW,
                        "OUT:i-run@" + FOURTEEN_DAYS_AGO + "~" + NOW);

        List<HourlySample> rows = stored(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(rows).filteredOn(s -> s.hourStart().isBefore(FOURTEEN_DAYS_AGO)).allSatisfy(s -> {
            assertThat(s.cpuAvg()).isEqualByComparingTo("30");
            assertThat(s.networkInBytes()).as("14일보다 앞은 네트워크를 읽지 않는다").isNull();
        });
        assertThat(rows).filteredOn(s -> !s.hourStart().isBefore(FOURTEEN_DAYS_AGO)).hasSize(10).allSatisfy(s -> {
            assertThat(s.cpuAvg()).isEqualByComparingTo("6");
            assertThat(s.cpuMax()).isEqualByComparingTo("23");
            assertThat(s.networkInBytes()).isEqualTo(1_000L);
            assertThat(s.networkOutBytes()).isEqualTo(2_000L);
        });
        assertThat(tracked().metricsCollectedUntil()).isEqualTo(NOW);

        UtilizationStats stats = UtilizationStats.byInstance(stored(FOURTEEN_DAYS_AGO)).get("i-run");
        assertThat(stats.cpuAvg()).isEqualByComparingTo("6.00");
        assertThat(stats.cpuMax()).isEqualByComparingTo("23.00");
        assertThat(stats.networkBytes()).isEqualTo(30_000L);

        JsonNode job = body(getJson("/api/v1/aws/accounts/" + accountId + "/sync-jobs")).get(0);
        assertThat(job.at("/cwRequestCount").asInt()).as("CPU 3 + 네트워크 2").isEqualTo(5);
    }

    @Test
    void 다음_수집은_네트워크도_마지막_수집_이후_구간만_읽는다() throws Exception {
        summary();
        aws.networkRequests.clear();
        clock.advance(Duration.ofMinutes(61));

        summary();

        assertThat(aws.networkRequests).hasSize(2)
                .allMatch(r -> r.contains("i-run@2026-10-08T01:00:00Z"), "2시간 겹쳐서 이어 읽기");
    }

    @Test
    void 지표_수집_전에_저장한_인스턴스는_최근_14일_CPU를_한_번_다시_읽는다() throws Exception {
        summary();
        // 지표 수집 기능이 생기기 전 상태: 사용 시간은 수집했지만 metricsCollectedUntil이 없다.
        instanceRepository.saveAll(owner, UUID.fromString(accountId), List.of(tracked().metricsCollected(null)));
        aws.cloudWatchRequests.clear();
        clock.advance(Duration.ofMinutes(61));

        summary();

        assertThat(aws.cloudWatchRequests).as("이어 읽기 1건 + 최근 14일 다시 읽기 1건")
                .hasSize(2)
                .anyMatch(r -> r.startsWith("i-run@2026-09-24T05:00:00Z"));
        assertThat(tracked().metricsCollectedUntil()).isNotNull();

        clock.advance(Duration.ofMinutes(61));
        aws.cloudWatchRequests.clear();
        summary();
        assertThat(aws.cloudWatchRequests).as("다시 읽기는 한 번뿐").hasSize(1);
    }

    @Test
    void 네트워크_수집이_실패해도_사용_시간은_저장하고_다음_수집에서_다시_읽는다() throws Exception {
        aws.networkError = new AwsAccessException(Category.UNAVAILABLE, "ServiceUnavailable",
                "cloudwatch:GetMetricStatistics", "AWS 서비스가 일시적으로 응답하지 않습니다 (cloudwatch:GetMetricStatistics).");

        JsonNode first = summary();

        assertThat(first.at("/usageHours").decimalValue()).isEqualByComparingTo("34.00");
        assertThat(first.at("/freshness/warnings").toString()).contains("다음 수집 때 다시");
        assertThat(tracked().metricsCollectedUntil()).as("실패하면 진행 표시를 옮기지 않는다").isNull();
        assertThat(tracked().usageCollectedUntil()).isEqualTo(NOW);

        aws.networkError = null;
        aws.networkRequests.clear();
        clock.advance(Duration.ofMinutes(61));
        summary();

        assertThat(aws.networkRequests).as("최근 14일을 처음부터 다시 (14일 전 시각을 정시로 올림)").hasSize(2)
                .allMatch(r -> r.contains("i-run@2026-09-24T05:00:00Z"));
        assertThat(stored(FOURTEEN_DAYS_AGO)).filteredOn(s -> s.hourStart().equals(Instant.parse("2026-10-07T00:00:00Z")))
                .singleElement().satisfies(s -> assertThat(s.networkInBytes()).isEqualTo(1_000L));
    }

    private void addStoppedInstance() {
        aws.instances.add(Fixtures.instance("i-stop", "m5.large", "stopped", Instant.parse("2026-10-01T00:00:00Z")));
        aws.addCpuHours("i-stop", Instant.parse("2026-10-01T00:00:00Z"), 5, 4, 9);
        aws.addNetworkHours("i-stop", Instant.parse("2026-10-01T00:00:00Z"), 5, 100, 100);
    }

    private TrackedInstance tracked(String instanceId) {
        return instanceRepository.findAllByOwnerIdAndAccountId(owner, UUID.fromString(accountId)).stream()
                .filter(t -> t.instanceId().equals(instanceId)).findFirst().orElseThrow();
    }

    @Test
    void 지표_수집_전에_저장한_중지_인스턴스도_사용_시간과_따로_지표를_한_번_채운다() throws Exception {
        addStoppedInstance();
        summary();
        instanceRepository.saveAll(owner, UUID.fromString(accountId), List.of(tracked("i-stop").metricsCollected(null)));
        aws.cloudWatchRequests.clear();
        aws.networkRequests.clear();
        clock.advance(Duration.ofMinutes(61));

        summary();

        assertThat(aws.cloudWatchRequests).as("꺼져 있어 사용 시간은 새로 읽지 않고, 최근 14일 CPU만 한 번 다시 읽는다")
                .filteredOn(r -> r.startsWith("i-stop@")).hasSize(1);
        assertThat(aws.networkRequests).filteredOn(r -> r.contains("i-stop@")).hasSize(2);
        assertThat(tracked("i-stop").metricsCollectedUntil()).isNotNull();
        assertThat(tracked("i-stop").usageCollectedUntil()).as("사용 시간 진행은 그대로").isEqualTo(NOW);

        aws.cloudWatchRequests.clear();
        aws.networkRequests.clear();
        clock.advance(Duration.ofMinutes(61));
        summary();
        assertThat(aws.cloudWatchRequests).noneMatch(r -> r.startsWith("i-stop@"));
        assertThat(aws.networkRequests).as("따라잡은 뒤에는 꺼진 인스턴스를 부르지 않는다").noneMatch(r -> r.contains("i-stop@"));
    }

    @Test
    void 네트워크_수집이_실패한_뒤_꺼져_있어도_다음_수집에서_다시_읽는다() throws Exception {
        addStoppedInstance();
        aws.networkError = new AwsAccessException(Category.UNAVAILABLE, "ServiceUnavailable",
                "cloudwatch:GetMetricStatistics", "AWS 서비스가 일시적으로 응답하지 않습니다 (cloudwatch:GetMetricStatistics).");
        summary();
        assertThat(tracked("i-stop").metricsCollectedUntil()).isNull();

        aws.networkError = null;
        aws.networkRequests.clear();
        clock.advance(Duration.ofMinutes(61));
        summary();

        assertThat(aws.networkRequests).filteredOn(r -> r.contains("i-stop@")).hasSize(2);
        assertThat(tracked("i-stop").metricsCollectedUntil()).isNotNull();
        assertThat(stored(Instant.parse("2026-10-01T00:00:00Z")))
                .filteredOn(s -> s.instanceId().equals("i-stop"))
                .allSatisfy(s -> assertThat(s.networkInBytes()).isEqualTo(100L));
    }
}
