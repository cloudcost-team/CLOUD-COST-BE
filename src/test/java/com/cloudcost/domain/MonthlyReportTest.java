package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.cost.CostEstimator.InstanceCost;
import com.cloudcost.domain.report.MonthlyReport;
import com.cloudcost.domain.report.MonthlyReport.Share;
import com.cloudcost.domain.report.MonthlyReport.Status;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonthlyReportTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);

    @Test
    void 상태는_기록_없음_첫_달_진행_중_확정_순으로_정한다() {
        YearMonth first = YearMonth.of(2026, 8);
        assertThat(MonthlyReport.status(YearMonth.of(2026, 7), OCT, first, 100)).isEqualTo(Status.EMPTY);
        assertThat(MonthlyReport.status(YearMonth.of(2026, 9), OCT, first, 0)).isEqualTo(Status.EMPTY);
        assertThat(MonthlyReport.status(first, OCT, first, 100)).isEqualTo(Status.FIRST_MONTH);
        assertThat(MonthlyReport.status(OCT, OCT, OCT, 100)).as("첫 달이 이번 달이면 첫 달").isEqualTo(Status.FIRST_MONTH);
        assertThat(MonthlyReport.status(OCT, OCT, first, 100)).isEqualTo(Status.PREVIEW);
        assertThat(MonthlyReport.status(YearMonth.of(2026, 9), OCT, first, 100)).isEqualTo(Status.FINAL);
        assertThat(MonthlyReport.status(OCT, OCT, null, 0)).isEqualTo(Status.EMPTY);
    }

    @Test
    void 전월_대비는_전월이_없거나_0이면_null이다() {
        assertThat(MonthlyReport.changeRate(new BigDecimal("96"), new BigDecimal("100"))).isEqualByComparingTo("-4.0");
        assertThat(MonthlyReport.changeRate(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
        assertThat(MonthlyReport.changeRate(BigDecimal.TEN, null)).isNull();
        assertThat(MonthlyReport.changeRate(null, BigDecimal.TEN)).isNull();
    }

    @Test
    void 서버별_비중은_비용_큰_순_상위_3대와_기타로_나눈다() {
        List<Share> top = MonthlyReport.topServers(List.of(
            new InstanceCost("i-a", 60, BigDecimal.ONE, new BigDecimal("10")),
            new InstanceCost("i-b", 60, BigDecimal.ONE, new BigDecimal("40")),
            new InstanceCost("i-c", 60, BigDecimal.ONE, new BigDecimal("20")),
            new InstanceCost("i-d", 60, BigDecimal.ONE, new BigDecimal("25")),
            new InstanceCost("i-e", 60, BigDecimal.ONE, new BigDecimal("5")),
            new InstanceCost("i-x", 600, null, null)));

        assertThat(top).extracting(Share::instanceId).containsExactly("i-b", "i-d", "i-c", null);
        assertThat(top).extracting(Share::sharePercent).extracting(BigDecimal::toPlainString)
            .containsExactly("40.0", "25.0", "20.0", "15.0");
        assertThat(top.get(3).others()).isTrue();
        assertThat(top.get(3).costUsd()).isEqualByComparingTo("15");
        assertThat(top.get(3).usedMinutes()).as("단가 모르는 서버는 빼고 기타는 나머지 합").isEqualTo(120);
        assertThat(MonthlyReport.topServers(List.of(new InstanceCost("i-a", 60, BigDecimal.ONE, BigDecimal.ONE))))
            .as("3대 이하면 기타 없음").hasSize(1);
        assertThat(MonthlyReport.topServers(List.of())).isEmpty();
    }

    @Test
    void 마지막_날은_이번_달이면_오늘_지난_달이면_말일이다() {
        LocalDate today = LocalDate.of(2026, 10, 8);
        assertThat(MonthlyReport.lastDay(OCT, today)).isEqualTo(today);
        assertThat(MonthlyReport.lastDay(YearMonth.of(2026, 9), today)).isEqualTo(LocalDate.of(2026, 9, 30));
    }
}
