package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.alert.AlertRules;
import com.cloudcost.domain.alert.AlertRules.Candidate;
import com.cloudcost.domain.alert.AlertSettings;
import com.cloudcost.domain.alert.NotificationType;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import com.cloudcost.domain.spike.SpikeDetector.Spike;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlertRulesTest {

    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final AlertSettings ALL = AlertSettings.defaults();
    private static final DisplayCurrency KRW = DisplayCurrency.krw(
        new ExchangeRate(new BigDecimal("1300"), LocalDate.of(2026, 10, 7), "test", Instant.EPOCH));

    @Test
    void 예산은_넘은_기준_이하에서_켜_둔_가장_높은_기준으로_연결_월_기준마다_한_번_알린다() {
        Candidate c = AlertRules.budget(ACCOUNT, "운영 서버", YearMonth.of(2026, 10), 100, ALL).orElseThrow();

        assertThat(c.type()).isEqualTo(NotificationType.BUDGET);
        assertThat(c.message()).isEqualTo("운영 서버의 이번 달 예상 비용이 예산의 100%를 넘었어요");
        assertThat(c.eventKey()).isEqualTo("BUDGET:" + ACCOUNT + ":2026-10:100");

        AlertSettings no120 = new AlertSettings(true, true, false, true, true);
        assertThat(AlertRules.budget(ACCOUNT, "a", YearMonth.of(2026, 10), 120, no120))
            .get().extracting(Candidate::eventKey).asString().endsWith(":100");
        assertThat(AlertRules.budget(ACCOUNT, "a", YearMonth.of(2026, 10), null, ALL)).isEmpty();
        assertThat(AlertRules.budget(ACCOUNT, "a", YearMonth.of(2026, 10), 80,
            new AlertSettings(false, true, true, true, true))).isEmpty();
    }

    @Test
    void 급증은_급증일마다_한_번_알린다() {
        Spike spike = new Spike(LocalDate.of(2026, 10, 12), new BigDecimal("24"), BigDecimal.TEN, 30,
            new BigDecimal("2.4"), "i-0abc", 1440, 480);

        Candidate c = AlertRules.spike(ACCOUNT, spike, "web-server-1", ALL).orElseThrow();

        assertThat(c.message()).isEqualTo("10/12 비용 급증 · 평소의 2.4배 (web-server-1)");
        assertThat(c.eventKey()).isEqualTo("SPIKE:" + ACCOUNT + ":2026-10-12");
        assertThat(AlertRules.spike(ACCOUNT, spike, "x", new AlertSettings(true, true, true, false, true))).isEmpty();
        assertThat(AlertRules.spike(ACCOUNT, null, "x", ALL)).isEmpty();
    }

    @Test
    void 주간_요약은_지난주_월요일로_한_번만_전주와_비교한_문구를_만든다() {
        assertThat(AlertRules.lastWeekMonday(LocalDate.of(2026, 10, 8))).as("목요일").isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(AlertRules.lastWeekMonday(LocalDate.of(2026, 10, 12))).as("월요일").isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(AlertRules.lastWeekMonday(LocalDate.of(2026, 10, 11))).as("일요일").isEqualTo(LocalDate.of(2026, 9, 28));

        LocalDate monday = LocalDate.of(2026, 9, 28);
        Candidate down = AlertRules.weeklySummary(ACCOUNT, monday, new BigDecimal("21.846"), new BigDecimal("22.75"),
            KRW, ALL).orElseThrow();
        assertThat(down.message()).as("28,400원, 4% 감소").isEqualTo("지난주 추정 비용 ₩28,400 · 전주보다 4% 줄었어요");
        assertThat(down.eventKey()).isEqualTo("WEEKLY_SUMMARY:" + ACCOUNT + ":2026-09-28");

        assertThat(AlertRules.weeklySummary(ACCOUNT, monday, new BigDecimal("12"), new BigDecimal("10"),
            DisplayCurrency.usd(), ALL)).get().extracting(Candidate::message)
            .isEqualTo("지난주 추정 비용 $12.00 · 전주보다 20% 늘었어요");
        assertThat(AlertRules.weeklySummary(ACCOUNT, monday, new BigDecimal("10"), null, DisplayCurrency.usd(), ALL))
            .get().extracting(Candidate::message).as("전주 기록이 없으면 비교하지 않음").isEqualTo("지난주 추정 비용 $10.00");
        assertThat(AlertRules.weeklySummary(ACCOUNT, monday, BigDecimal.ZERO, BigDecimal.ZERO, DisplayCurrency.usd(), ALL))
            .as("두 주 모두 0원이면 알리지 않음").isEmpty();
        assertThat(AlertRules.weeklySummary(ACCOUNT, monday, BigDecimal.TEN, BigDecimal.TEN, DisplayCurrency.usd(),
            new AlertSettings(true, true, true, true, false))).isEmpty();
    }

    @Test
    void 금액_기호는_서버_로케일과_상관없다() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertThat(AlertRules.weeklySummary(ACCOUNT, LocalDate.of(2026, 9, 28), new BigDecimal("21.846"), null, KRW,
                ALL)).get().extracting(Candidate::message).isEqualTo("지난주 추정 비용 ₩28,400");
            assertThat(AlertRules.weeklySummary(ACCOUNT, LocalDate.of(2026, 9, 28), new BigDecimal("1234.5"), null,
                DisplayCurrency.usd(), ALL)).get().extracting(Candidate::message).isEqualTo("지난주 추정 비용 $1,234.50");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }
}
