package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.application.port.Ec2UsageRepository;
import com.cloudcost.domain.usage.HourlySample;
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
 * 수집 정합성: upsert 멱등성, 일부 실패 시 성공분 보존과 실패 구간 재수집, 요청 제한 재시도, 연결 상태 전이.
 * 소급 구간(NOW=2026-10-08T03:00Z, 90일): [07-10T03, 08-09T03) [08-09T03, 09-08T03) [09-08T03, 10-08T03)
 */
class CollectionIntegrityTest extends IntegrationTestBase {

    private static final String WINDOW_2 = "2026-08-09T03:00:00Z";

    @Autowired Ec2UsageRepository usageRepository;

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", Instant.parse("2026-10-07T00:00:00Z")));
        aws.addHours("i-run", Instant.parse("2026-08-20T00:00:00Z"), 5, 60);   // 2번째 구간
        aws.addHours("i-run", Instant.parse("2026-10-07T00:00:00Z"), 10, 60);  // 3번째 구간
        aws.addHours("i-run", Instant.parse("2026-10-06T05:00:00Z"), 1, 10);
        aws.instances.add(Fixtures.instance("i-stop", "t3.medium", "stopped", Instant.parse("2026-10-05T00:00:00Z")));
        aws.addHours("i-stop", Instant.parse("2026-10-05T00:00:00Z"), 3, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
    }

    private JsonNode summary() throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/costs?currency=USD&from=2026-07-10&to=2026-10-08")
                .andExpect(status().isOk()));
    }

    private JsonNode instances() throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/instances?currency=USD").andExpect(status().isOk()));
    }

    private JsonNode account() throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId)).at("/account");
    }

    private JsonNode jobs() throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/sync-jobs"));
    }

    private static AwsAccessException error(Category category, String code, String message) {
        return new AwsAccessException(category, code, "cloudwatch:GetMetricStatistics", message);
    }

    @Test
    void 같은_시간을_여러_번_저장해도_행이_늘지_않고_값만_갱신된다() throws Exception {
        UUID id = UUID.fromString(accountId);
        Instant h0 = Instant.parse("2026-10-07T00:00:00Z");
        Instant h1 = h0.plusSeconds(3600);
        Instant h2 = h1.plusSeconds(3600);

        int first = usageRepository.upsertHourly(owner, id, List.of(
                new HourlySample("i-a", h0, BigDecimal.valueOf(60)),
                new HourlySample("i-a", h1, BigDecimal.valueOf(60)),
                new HourlySample("i-a", h1, BigDecimal.valueOf(30))), NOW);
        assertThat(first).as("한 목록 안의 같은 시각은 하나로 합친다 (UNIQUE 위반 없음)").isEqualTo(2);

        usageRepository.upsertHourly(owner, id, List.of(
                new HourlySample("i-a", h1, BigDecimal.valueOf(45)),
                new HourlySample("i-a", h2, BigDecimal.valueOf(60))), NOW.plusSeconds(60));

        List<HourlySample> stored = usageRepository.findHourly(owner, id, h0, h2.plusSeconds(3600));
        assertThat(stored).hasSize(3);
        assertThat(stored).filteredOn(s -> s.hourStart().equals(h1))
                .singleElement()
                .satisfies(s -> assertThat(s.sampleCount()).isEqualByComparingTo("45"));
        assertThat(usageRepository.findHourly("someone-else", id, h0, h2.plusSeconds(3600)))
                .as("owner가 다르면 보이지 않는다").isEmpty();
    }

    @Test
    void 백필_중_한_구간이_실패하면_성공분은_남기고_다음_수집에서_실패한_구간부터_다시_읽는다() throws Exception {
        AwsAccessException unavailable = error(Category.UNAVAILABLE, "ServiceUnavailable",
                "AWS 서비스가 일시적으로 응답하지 않습니다 (cloudwatch:GetMetricStatistics). 잠시 후 다시 시도하세요.");
        aws.cloudWatchFailure = r -> r.startsWith("i-run@" + WINDOW_2) ? unavailable : null;

        JsonNode first = summary();

        assertThat(aws.cloudWatchCalls).as("i-run: 1구간 + 2구간 3회 시도, i-stop: 3구간 전부").isEqualTo(7);
        assertThat(aws.cloudWatchRequests).as("실패한 구간 뒤는 읽지 않는다 (진행 표시가 실패 구간을 건너뛰지 않게)")
                .noneMatch(r -> r.startsWith("i-run@2026-09-08"));
        assertThat(pauser.pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(first.at("/usageHours").decimalValue()).as("성공한 i-stop은 저장됨").isEqualByComparingTo("3.00");
        assertThat(first.at("/freshness/warnings").toString()).contains("사용량 수집 실패").contains("다음 수집 때 다시");

        JsonNode job = jobs().get(0);
        assertThat(job.at("/status").asText()).isEqualTo("PARTIAL");
        assertThat(job.at("/usageRefreshed").asBoolean()).isFalse();
        assertThat(job.at("/cwRequestCount").asInt()).as("재시도도 고객 계정 호출 수에 센다").isEqualTo(7);
        assertThat(job.at("/error/code").asText()).isEqualTo("UNAVAILABLE");
        assertThat(job.at("/error/message").asText()).contains("일시적으로 응답하지 않습니다");
        assertThat(account().at("/status").asText()).as("일시 오류는 상태를 바꾸지 않는다").isEqualTo("ACTIVE");
        assertThat(account().at("/lastError/code").asText()).isEqualTo("UNAVAILABLE");

        aws.cloudWatchFailure = r -> null;
        aws.cloudWatchRequests.clear();
        clock.advance(Duration.ofMinutes(61));
        JsonNode second = summary();

        assertThat(aws.cloudWatchRequests).as("i-run은 성공한 1구간 끝(2시간 겹침)부터, i-stop은 꺼져 있어 부르지 않는다")
                .hasSize(3)
                .allMatch(r -> r.startsWith("i-run@"))
                .first().asString().startsWith("i-run@2026-08-09T01:00:00Z");
        assertThat(second.at("/usageHours").decimalValue()).isEqualByComparingTo("18.17");
        assertThat(jobs().get(0).at("/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(account().at("/lastError").isNull()).as("성공하면 오류를 지운다").isTrue();

        clock.advance(Duration.ofMinutes(61));
        assertThat(summary().at("/usageHours").decimalValue()).as("겹친 구간을 다시 읽어도 중복되지 않는다")
                .isEqualByComparingTo("18.17");
    }

    @Test
    void 요청_제한은_백오프로_다시_시도해_성공하면_전부_저장한다() throws Exception {
        int[] throttles = {2};
        aws.cloudWatchFailure = r -> throttles[0]-- > 0
                ? error(Category.THROTTLED, "Throttling", "Throttling: AWS 요청이 일시적으로 제한되었습니다.")
                : null;

        JsonNode body = summary();

        assertThat(aws.cloudWatchCalls).as("6구간 + 재시도 2회").isEqualTo(8);
        assertThat(pauser.pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(body.at("/usageHours").decimalValue()).isEqualByComparingTo("18.17");
        assertThat(body.at("/freshness/warnings")).isEmpty();
        JsonNode job = jobs().get(0);
        assertThat(job.at("/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(job.at("/cwRequestCount").asInt()).isEqualTo(8);
    }

    @Test
    void 요청_제한이_계속되면_이번_수집을_멈추고_다음_수집에서_이어서_가져온다() throws Exception {
        aws.cloudWatchError = error(Category.THROTTLED, "RequestLimitExceeded",
                "RequestLimitExceeded: AWS 요청이 일시적으로 제한되었습니다 (cloudwatch:GetMetricStatistics).");

        JsonNode first = instances();
        assertThat(first.at("/instances")).as("인스턴스 정보는 보여 준다").hasSize(2);
        assertThat(aws.cloudWatchCalls).as("첫 구간 3회 시도 후 다른 인스턴스는 부르지 않는다").isEqualTo(3);
        assertThat(jobs().get(0).at("/status").asText()).isEqualTo("PARTIAL");
        assertThat(jobs().get(0).at("/error/code").asText()).isEqualTo("THROTTLED");
        assertThat(account().at("/status").asText()).isEqualTo("ACTIVE");

        aws.cloudWatchError = null;
        clock.advance(Duration.ofMinutes(61));
        assertThat(summary().at("/usageHours").decimalValue()).isEqualByComparingTo("18.17");
        assertThat(account().at("/lastError").isNull()).isTrue();
    }

    @Test
    void Role이_거부되면_ERROR가_되고_1시간_뒤_재확인에서_성공하면_ACTIVE로_복구된다() throws Exception {
        instances();
        clock.advance(Duration.ofMinutes(15));
        aws.assumeError = new AwsAccessException(Category.ASSUME_ROLE_DENIED, "AccessDenied", "sts:AssumeRole",
                "AccessDenied: Role을 맡을 수 없습니다. ⑥ CloudCost Insight 스택이나 Role이 삭제되지 않았는지 확인하세요.");

        JsonNode failed = instances();
        assertThat(failed.at("/instances")).as("저장된 데이터는 계속 보여 준다").hasSize(2);
        assertThat(failed.at("/freshness/warnings").toString()).contains("Role을 맡을 수 없습니다");
        JsonNode account = account();
        assertThat(account.at("/status").asText()).isEqualTo("ERROR");
        assertThat(account.at("/lastError/code").asText()).isEqualTo("ASSUME_ROLE_DENIED");
        assertThat(aws.assumeCalls).isEqualTo(2);
        assertThat(pauser.pauses).as("Role 거부는 재시도하지 않는다").isEmpty();

        clock.advance(Duration.ofMinutes(10));
        JsonNode waiting = instances();
        assertThat(aws.assumeCalls).as("ERROR 연결은 1시간에 한 번만 다시 확인한다").isEqualTo(2);
        assertThat(waiting.at("/freshness/warnings").toString()).contains("AWS 연결에 문제가 있어").contains("Role을 맡을 수 없습니다");

        aws.assumeError = null;
        clock.advance(Duration.ofMinutes(40));
        JsonNode recovered = instances();
        assertThat(aws.assumeCalls).isEqualTo(3);
        assertThat(recovered.at("/freshness/warnings")).isEmpty();
        assertThat(account().at("/status").asText()).isEqualTo("ACTIVE");
        assertThat(account().at("/lastError").isNull()).isTrue();
    }

    @Test
    void 리전이_비활성화되면_ERROR로_바꾸고_원인을_한국어로_남긴다() throws Exception {
        aws.describeError = new AwsAccessException(Category.REGION_DISABLED, "OptInRequired", "ec2:DescribeInstances",
                "OptInRequired: 연결한 리전에서 요청이 거부되었습니다 (ec2:DescribeInstances). 리전이 AWS 계정에서 활성화되어 있는지 확인하세요.");

        JsonNode error = body(getJson("/api/v1/aws/accounts/" + accountId + "/instances")
                .andExpect(status().isUnprocessableEntity()));
        assertThat(error.at("/error/code").asText()).isEqualTo("AWS_REGION_DISABLED");
        JsonNode account = account();
        assertThat(account.at("/status").asText()).isEqualTo("ERROR");
        assertThat(account.at("/lastError/message").asText()).contains("리전이 AWS 계정에서 활성화되어 있는지");
        assertThat(jobs().get(0).at("/status").asText()).isEqualTo("FAILED");

        clock.advance(Duration.ofMinutes(5));
        getJson("/api/v1/aws/accounts/" + accountId + "/instances").andExpect(status().isOk());
        assertThat(aws.describeCalls).as("1시간 안에는 다시 부르지 않는다").isEqualTo(1);
    }

    @Test
    void 일시적인_AWS_장애는_연결을_ERROR로_바꾸지_않는다() throws Exception {
        instances();
        clock.advance(Duration.ofMinutes(11));
        aws.describeError = new AwsAccessException(Category.UNAVAILABLE, "ClientError", "ec2:DescribeInstances",
                "AWS에 연결하지 못했습니다 (ec2:DescribeInstances). 네트워크 상태를 확인하고 잠시 후 다시 시도하세요.");

        JsonNode body = instances();
        assertThat(body.at("/freshness/warnings").toString()).contains("AWS에 연결하지 못했습니다");
        assertThat(aws.describeCalls).as("1회 + 재시도 2회").isEqualTo(4);
        assertThat(account().at("/status").asText()).isEqualTo("ACTIVE");
        assertThat(account().at("/lastError/code").asText()).isEqualTo("UNAVAILABLE");
        assertThat(jobs().get(0).at("/status").asText()).isEqualTo("FAILED");
    }
}
