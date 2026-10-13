package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 조회 시점 수집(캐시): EC2 동기화(10분) · 사용량 동기화(CloudWatch 1시간 1회) · 비용 계산.
 */
class UsageRefreshTest extends IntegrationTestBase {

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        // i-run: 실행 중 (KST 10/07 09:00~18:59 = 10시간 꽉 참, 10/06 14:00 대에 10분)
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", Instant.parse("2026-10-07T00:00:00Z")));
        aws.addHours("i-run", Instant.parse("2026-10-07T00:00:00Z"), 10, 60);
        aws.addHours("i-run", Instant.parse("2026-10-06T05:00:00Z"), 1, 10);
        // i-stop: 정지 상태 (KST 10/05 09:00~11:59 = 3시간)
        aws.instances.add(Fixtures.instance("i-stop", "t3.medium", "stopped", Instant.parse("2026-10-05T00:00:00Z")));
        aws.addHours("i-stop", Instant.parse("2026-10-05T00:00:00Z"), 3, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
    }

    private JsonNode instances() throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/instances").andExpect(status().isOk()));
    }

    private JsonNode daily(String from, String to) throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/costs/daily?from=" + from + "&to=" + to)
                .andExpect(status().isOk()));
    }

    @Test
    void EC2_동기화_처음_조회하면_DescribeInstances로_가져와_DB에_저장한다() throws Exception {
        JsonNode body = instances();

        assertThat(aws.describeCalls).isEqualTo(1);
        assertThat(body.at("/dataSource").asText()).isEqualTo("aws");
        assertThat(body.at("/costBasis").asText()).isEqualTo("estimated_on_demand");
        assertThat(body.at("/instances")).hasSize(2);
        JsonNode running = body.at("/instances/0");
        assertThat(running.at("/instanceId").asText()).isEqualTo("i-run");
        assertThat(running.at("/state").asText()).isEqualTo("running");
        assertThat(running.at("/availabilityZone").asText()).isEqualTo("ap-northeast-2a");
        assertThat(running.at("/estimatedHourlyCost").decimalValue()).isEqualByComparingTo("0.0416");
        assertThat(running.at("/currentRun/hours").decimalValue()).isEqualByComparingTo("27.00");
        assertThat(body.at("/instances/1/currentRun").isNull()).isTrue();
        assertThat(body.at("/freshness/instancesFromCache").asBoolean()).isFalse();
        assertThat(aws.openSessions).isZero();
    }

    @Test
    void EC2_동기화_10분_안의_재조회는_DB에서_응답하고_지나면_다시_가져온다() throws Exception {
        instances();
        clock.advance(Duration.ofMinutes(5));
        JsonNode cached = instances();
        assertThat(aws.describeCalls).isEqualTo(1);
        assertThat(aws.assumeCalls).as("캐시 안이면 AssumeRole도 하지 않는다").isEqualTo(1);
        assertThat(cached.at("/freshness/instancesFromCache").asBoolean()).isTrue();
        assertThat(cached.at("/freshness/usageFromCache").asBoolean()).isTrue();

        clock.advance(Duration.ofMinutes(6));
        aws.instances.removeIf(i -> i.instanceId().equals("i-stop"));
        JsonNode refreshed = instances();
        assertThat(aws.describeCalls).isEqualTo(2);
        assertThat(refreshed.at("/instances")).as("같은 인스턴스는 갱신만 하고 중복 저장하지 않는다").hasSize(2);
        assertThat(refreshed.at("/instances/1/instanceId").asText()).isEqualTo("i-stop");
        assertThat(refreshed.at("/instances/1/present").asBoolean()).as("사라진 인스턴스는 기록을 남기고 present=false").isFalse();
    }

    @Test
    void 사용량_동기화_CloudWatch는_1시간에_1회만_부르고_그_안에서는_DB_값을_쓴다() throws Exception {
        daily("2026-10-01", "2026-10-08");
        assertThat(aws.cloudWatchCalls).as("처음: 인스턴스 2대 × 90일(30일씩 3번)").isEqualTo(6);

        clock.advance(Duration.ofMinutes(30));
        JsonNode cached = daily("2026-10-01", "2026-10-08");
        assertThat(aws.cloudWatchCalls).as("1시간 안 재조회: CloudWatch 호출 없음").isEqualTo(6);
        assertThat(aws.describeCalls).as("인스턴스 정보는 10분이 지나 갱신").isEqualTo(2);
        assertThat(cached.at("/freshness/usageFromCache").asBoolean()).isTrue();
        assertThat(cached.at("/freshness/nextUsageRefreshAt").asText()).isEqualTo("2026-10-08T04:00:00Z");
        assertThat(cached.at("/usageHours").decimalValue()).isEqualByComparingTo("13.17");

        clock.advance(Duration.ofMinutes(31));
        JsonNode refreshed = daily("2026-10-01", "2026-10-08");
        assertThat(aws.cloudWatchCalls).as("1시간 후: 실행 중인 인스턴스만, 마지막 수집 이후 구간 1번").isEqualTo(7);
        assertThat(aws.cloudWatchRequests.getLast()).startsWith("i-run@2026-10-08T01:00:00Z");
        assertThat(refreshed.at("/freshness/usageFromCache").asBoolean()).isFalse();
        assertThat(refreshed.at("/usageHours").decimalValue()).as("다시 읽어도 중복 저장되지 않는다").isEqualByComparingTo("13.17");

        JsonNode jobs = body(getJson("/api/v1/aws/accounts/" + accountId + "/sync-jobs"));
        assertThat(jobs).hasSize(3);
        assertThat(jobs.get(0).at("/cwRequestCount").asInt()).isEqualTo(1);
        assertThat(jobs.get(1).at("/cwRequestCount").asInt()).isZero();
        assertThat(jobs.get(2).at("/cwRequestCount").asInt()).isEqualTo(6);
        assertThat(jobs.get(2).at("/status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void 비용_동기화_일별_사용_시간과_추정_비용() throws Exception {
        JsonNode body = daily("2026-10-05", "2026-10-07");

        assertThat(body.at("/costBasis").asText()).isEqualTo("estimated_on_demand");
        assertThat(body.at("/currency").asText()).isEqualTo("USD");
        assertThat(body.at("/timezone").asText()).isEqualTo("Asia/Seoul");
        assertThat(body.at("/days")).hasSize(3);
        // 10/05: i-stop 3시간
        assertThat(body.at("/days/0/date").asText()).isEqualTo("2026-10-05");
        assertThat(body.at("/days/0/usageHours").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(body.at("/days/0/estimatedCost").decimalValue()).isEqualByComparingTo("0.12");
        // 10/06: i-run 10분
        assertThat(body.at("/days/1/usageHours").decimalValue()).isEqualByComparingTo("0.17");
        assertThat(body.at("/days/1/estimatedCost").decimalValue()).isEqualByComparingTo("0.01");
        // 10/07: i-run 10시간 → 10 × 0.0416 = 0.416
        assertThat(body.at("/days/2/usageHours").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(body.at("/days/2/estimatedCost").decimalValue()).isEqualByComparingTo("0.42");
        assertThat(body.at("/days/2/instances/0/instanceId").asText()).isEqualTo("i-run");
        assertThat(body.at("/usageHours").decimalValue()).isEqualByComparingTo("13.17");
        assertThat(body.at("/estimatedCost").decimalValue()).isEqualByComparingTo("0.55");
        assertThat(body.at("/notes").toString()).contains("실제 AWS 청구 금액이 아닙니다").contains("Savings Plans");
    }

    @Test
    void 기간을_넣지_않으면_기본_기간으로_응답한다() throws Exception {
        JsonNode summary = body(getJson("/api/v1/aws/accounts/" + accountId + "/costs").andExpect(status().isOk()));
        assertThat(summary.at("/from").asText()).isEqualTo("2026-10-01");
        assertThat(summary.at("/to").asText()).isEqualTo("2026-10-08");
        assertThat(summary.at("/usageHours").decimalValue()).isEqualByComparingTo("13.17");
        assertThat(summary.at("/instances")).hasSize(2);

        JsonNode daily = body(getJson("/api/v1/aws/accounts/" + accountId + "/costs/daily").andExpect(status().isOk()));
        assertThat(daily.at("/days")).hasSize(30);

        JsonNode monthly = body(getJson("/api/v1/aws/accounts/" + accountId + "/costs/monthly").andExpect(status().isOk()));
        assertThat(monthly.at("/from").asText()).isEqualTo("2026-08");
        assertThat(monthly.at("/months")).hasSize(3);
        assertThat(monthly.at("/months/2/month").asText()).isEqualTo("2026-10");
        assertThat(monthly.at("/months/2/usageHours").decimalValue()).isEqualByComparingTo("13.17");
        assertThat(monthly.at("/months/2/estimatedCost").decimalValue()).isEqualByComparingTo("0.55");
    }

    @Test
    void 단가를_찾지_못한_인스턴스는_금액이_null이고_안내를_붙인다() throws Exception {
        aws.instances.add(Fixtures.instance("i-odd", "x9.unknown", "running", Instant.parse("2026-10-07T00:00:00Z")));
        aws.addHours("i-odd", Instant.parse("2026-10-07T00:00:00Z"), 2, 60);

        JsonNode body = daily("2026-10-07", "2026-10-07");
        JsonNode odd = null;
        for (JsonNode row : body.at("/days/0/instances")) {
            if (row.at("/instanceId").asText().equals("i-odd")) {
                odd = row;
            }
        }
        assertThat(odd).isNotNull();
        assertThat(odd.at("/usageHours").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(odd.at("/estimatedCost").isNull()).isTrue();
        assertThat(odd.at("/note").asText()).isEqualTo("해당 인스턴스의 온디맨드 단가를 조회할 수 없습니다.");
        assertThat(body.at("/days/0/estimatedCost").decimalValue()).as("단가 있는 인스턴스만 합산").isEqualByComparingTo("0.42");
        assertThat(body.at("/notes").toString()).contains("단가를 찾지 못한 인스턴스 1개");
    }

    @Test
    void CloudWatch_권한이_없으면_인스턴스는_보여_주고_경고하며_1시간_안에_다시_부르지_않는다() throws Exception {
        aws.cloudWatchError = new AwsAccessException(Category.PERMISSION_DENIED, "AccessDenied",
                "cloudwatch:GetMetricStatistics", "AccessDenied: cloudwatch:GetMetricStatistics 권한이 없습니다.");

        JsonNode body = daily("2026-10-07", "2026-10-07");
        assertThat(body.at("/freshness/warnings").toString()).contains("cloudwatch:GetMetricStatistics 권한이 없습니다");
        assertThat(aws.cloudWatchCalls).isEqualTo(1);

        clock.advance(Duration.ofMinutes(20));
        daily("2026-10-07", "2026-10-07");
        assertThat(aws.cloudWatchCalls).as("실패해도 1시간 1회 제한을 지킨다").isEqualTo(1);

        JsonNode jobs = body(getJson("/api/v1/aws/accounts/" + accountId + "/sync-jobs"));
        assertThat(jobs.get(1).at("/status").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void 처음_수집에서_AWS_연결이_실패하면_원인을_그대로_알린다() throws Exception {
        aws.describeError = new AwsAccessException(Category.UNAVAILABLE, "ClientError", "ec2:DescribeInstances",
                "AWS에 연결하지 못했습니다 (ec2:DescribeInstances).");
        getJson("/api/v1/aws/accounts/" + accountId + "/instances")
                .andExpect(status().isBadGateway());
    }

    @Test
    void 이전_데이터가_있으면_AWS_실패_시_저장된_데이터와_경고를_준다() throws Exception {
        instances();
        clock.advance(Duration.ofMinutes(15));
        aws.assumeError = new AwsAccessException(Category.ASSUME_ROLE_DENIED, "AccessDenied", "sts:AssumeRole",
                "AccessDenied: Role을 맡을 수 없습니다.");
        JsonNode body = instances();
        assertThat(body.at("/instances")).hasSize(2);
        assertThat(body.at("/freshness/warnings").toString()).contains("Role을 맡을 수 없습니다");
    }
}
