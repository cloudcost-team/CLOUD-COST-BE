package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.ScheduledCollectionService.Outcome;
import com.cloudcost.application.ScheduledCollectionService.Target;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.port.NotificationRepository;
import com.cloudcost.fakes.Fixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** 예약 수집 (ADR-019): 사용자가 접속하지 않아도 수집하고 알림 조건을 평가한다. */
class ScheduledCollectionTest extends IntegrationTestBase {

    @Autowired ScheduledCollectionService service;
    @Autowired NotificationRepository notifications;
    @Autowired JdbcTemplate jdbc;

    private UUID accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = UUID.fromString(activeAccount());
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addHours("i-run", Instant.parse("2026-09-01T00:00:00Z"), 37 * 24 + 3, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
        call(put("/api/v1/aws/accounts/" + accountId + "/budget").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":30,\"currency\":\"USD\"}")).andExpect(status().isOk());
    }

    private Integer scheduledJobs() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_jobs WHERE owner_id = ? AND account_id = ? AND trigger_type = 'SCHEDULED'",
                Integer.class, owner, accountId);
    }

    @Test
    void 수집할_연결만_고른다() throws Exception {
        String pending = createAccount();

        assertThat(service.targets()).contains(new Target(owner, accountId))
                .doesNotContain(new Target(owner, UUID.fromString(pending)));
    }

    @Test
    void 접속하지_않아도_수집하고_알림을_만든다() {
        assertThat(notifications.countUnread(owner)).isZero();

        assertThat(service.collect(owner, accountId)).isEqualTo(Outcome.DONE);

        assertThat(scheduledJobs()).isEqualTo(1);
        assertThat(aws.cloudWatchCalls).isPositive();
        assertThat(notifications.countUnread(owner)).as("예산 100% + 주간 요약").isEqualTo(2);

        int calls = aws.cloudWatchCalls;
        service.collect(owner, accountId);
        assertThat(aws.cloudWatchCalls).as("1시간 캐시 규칙은 예약 수집에도 같다").isEqualTo(calls);
        assertThat(notifications.countUnread(owner)).as("같은 알림을 또 만들지 않는다").isEqualTo(2);
    }

    @Test
    void owner가_맞지_않으면_건너뛴다() {
        assertThat(service.collect("someone-else", accountId)).isEqualTo(Outcome.SKIPPED);
        assertThat(service.collect(owner, UUID.randomUUID())).isEqualTo(Outcome.SKIPPED);

        assertThat(scheduledJobs()).isZero();
        assertThat(aws.assumeCalls).isZero();
    }

    @Test
    void AWS_오류는_연결_상태에_남기고_작업은_끝낸다() {
        aws.assumeError = new AwsAccessException(AwsAccessException.Category.ASSUME_ROLE_DENIED, "AccessDenied",
                "sts:AssumeRole", "Role을 맡지 못했습니다.");

        assertThat(service.collect(owner, accountId)).isEqualTo(Outcome.DONE);
        assertThat(jdbc.queryForObject("SELECT status FROM aws_accounts WHERE id = ?", String.class, accountId))
                .isEqualTo("ERROR");
    }
}
