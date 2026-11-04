package com.cloudcost.domain.alert;

import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.domain.spike.SpikeDetector.Spike;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.UUID;

/**
 * 알림 조건 → 만들 알림 (이슈 #35, ADR-014). 언제 평가할지·저장은 application이 정한다.
 * 발생 키(eventKey)로 같은 일을 한 번만 알린다: 예산은 연결·월·기준마다, 급증은 연결·급증일마다, 주간 요약은 연결·주(월~일)마다.
 */
public final class AlertRules {

    private AlertRules() {
    }

    /** 만들 알림 (아직 저장 전) */
    public record Candidate(UUID accountId, NotificationType type, String eventKey, String message) {
    }

    /**
     * @param reached 대시보드의 넘은 가장 높은 기준 (월말 예상 기준). 없으면 null
     */
    public static Optional<Candidate> budget(
        UUID accountId, String connectionName, YearMonth month, Integer reached, AlertSettings settings) {
        Integer threshold = settings.budgetThresholdToNotify(reached);
        if (threshold == null) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(accountId, NotificationType.BUDGET,
            "BUDGET:" + accountId + ":" + month + ":" + threshold,
            connectionName + "의 이번 달 예상 비용이 예산의 " + threshold + "%를 넘었어요"));
    }

    public static Optional<Candidate> spike(UUID accountId, Spike spike, String causeName, AlertSettings settings) {
        if (spike == null || !settings.spike()) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(accountId, NotificationType.SPIKE,
            "SPIKE:" + accountId + ":" + spike.date(), spike.shortMessage(causeName)));
    }

    /** 오늘 기준 지난주 월요일 (주는 월~일) */
    public static LocalDate lastWeekMonday(LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    /**
     * @param lastWeekUsd     지난주(월~일) 추정 비용
     * @param previousWeekUsd 그 전주 추정 비용. 기록이 온전하지 않으면 null (비교하지 않음)
     * @param display         문구에 쓸 통화 (그때의 환율로 적는다)
     */
    public static Optional<Candidate> weeklySummary(
        UUID accountId, LocalDate lastWeekMonday, BigDecimal lastWeekUsd, BigDecimal previousWeekUsd,
        DisplayCurrency display, AlertSettings settings) {
        if (!settings.weeklySummary() || lastWeekUsd == null) {
            return Optional.empty();
        }
        if (lastWeekUsd.signum() == 0 && (previousWeekUsd == null || previousWeekUsd.signum() == 0)) {
            return Optional.empty(); // 두 주 모두 쓴 게 없으면 알릴 게 없다
        }
        StringBuilder message = new StringBuilder("지난주 추정 비용 ").append(money(lastWeekUsd, display));
        if (previousWeekUsd != null && previousWeekUsd.signum() > 0) {
            BigDecimal rate = lastWeekUsd.subtract(previousWeekUsd).multiply(BigDecimal.valueOf(100))
                .divide(previousWeekUsd, 0, RoundingMode.HALF_UP);
            if (rate.signum() == 0) {
                message.append(" · 전주와 비슷해요");
            } else {
                message.append(" · 전주보다 ").append(rate.abs().toPlainString()).append("% ")
                    .append(rate.signum() > 0 ? "늘었어요" : "줄었어요");
            }
        }
        return Optional.of(new Candidate(accountId, NotificationType.WEEKLY_SUMMARY,
            "WEEKLY_SUMMARY:" + accountId + ":" + lastWeekMonday, message.toString()));
    }

    /** ₩28,400 · $21.30 */
    static String money(BigDecimal usd, DisplayCurrency display) {
        BigDecimal amount = display.cost(usd);
        if (display.currency() == Currency.KRW) {
            return "₩" + new DecimalFormat("#,##0").format(amount);
        }
        return "$" + new DecimalFormat("#,##0.00").format(amount);
    }
}
