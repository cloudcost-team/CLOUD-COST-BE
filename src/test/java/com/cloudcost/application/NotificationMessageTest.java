package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.application.port.NotificationChannelGateway.Field;
import com.cloudcost.application.port.NotificationChannelGateway.Message;
import com.cloudcost.application.port.NotificationChannelGateway.Tone;
import com.cloudcost.domain.alert.AlertFacts;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.alert.NotificationType;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 알림 → 외부 채널 메시지 (Discord embed) 내용 */
class NotificationMessageTest {

    private static final DisplayCurrency KRW = DisplayCurrency.krw(
        new ExchangeRate(new BigDecimal("1300"), LocalDate.of(2026, 10, 7), "test", Instant.EPOCH));
    private final NotificationDispatchService service =
        new NotificationDispatchService(null, null, null, Clock.systemUTC(), "https://www.cloud-cost.cloud/");

    private static Notification notification(NotificationType type, String message, String key, AlertFacts facts) {
        return new Notification(UUID.randomUUID(), "o", UUID.randomUUID(), type, message, key, Instant.EPOCH, null, facts);
    }

    @Test
    void 예산_알림은_넘은_기준을_제목에_금액을_필드에_넣는다() {
        Message m = service.message(notification(NotificationType.BUDGET, "테스트서버의 이번 달 예상 비용이 예산의 120%를 넘었어요",
            "BUDGET:a:2026-10:120", AlertFacts.budget(120, new BigDecimal("40"), new BigDecimal("50"))), "테스트서버", KRW);

        assertThat(m.title()).isEqualTo("예산 120% 넘음");
        assertThat(m.description()).isEqualTo("테스트서버의 이번 달 월말 예상 비용이 예산의 120%를 넘었어요.");
        assertThat(m.tone()).isEqualTo(Tone.DANGER);
        assertThat(m.fields()).containsExactly(
            new Field("연결", "테스트서버"), new Field("월말 예상 비용", "₩65,000"), new Field("예산 금액", "₩52,000"));
        assertThat(m.footer()).isEqualTo("사용 시간 × 온디맨드 단가 기준");
        assertThat(m.url()).as("끝 슬래시를 겹치지 않는다").isEqualTo("https://www.cloud-cost.cloud/dashboard");

        Message at80 = service.message(notification(NotificationType.BUDGET, "x", "BUDGET:a:2026-10:80",
            AlertFacts.budget(80, BigDecimal.TEN, BigDecimal.TEN)), null, KRW);
        assertThat(at80.tone()).isEqualTo(Tone.WARNING);
        assertThat(at80.fields().getFirst()).isEqualTo(new Field("연결", "이름 없는 연결"));
    }

    @Test
    void 급증_알림은_날짜_배수_원인_서버를_필드에_넣는다() {
        AlertFacts facts = new AlertFacts(null, null, null, LocalDate.of(2026, 10, 12), new BigDecimal("2.4"), "web-1");
        Message m = service.message(notification(NotificationType.SPIKE, "10/12 비용 급증", "SPIKE:a:2026-10-12", facts),
            "테스트서버", KRW);

        assertThat(m.title()).isEqualTo("비용 급증");
        assertThat(m.tone()).isEqualTo(Tone.WARNING);
        assertThat(m.description()).isEqualTo("테스트서버의 10월 12일 비용이 평소의 2.4배예요.");
        assertThat(m.fields()).extracting(Field::name).containsExactly("연결", "날짜", "평소 대비", "원인 서버");
        assertThat(m.fields()).extracting(Field::value).containsExactly("테스트서버", "10월 12일", "2.4배", "web-1");
        assertThat(m.url()).endsWith("/dashboard");
    }

    @Test
    void 주간_요약은_파랑이고_알림_화면으로_연결한다() {
        Message m = service.message(notification(NotificationType.WEEKLY_SUMMARY, "지난주 비용 ₩9,085 · 전주와 비슷해요",
            "WEEKLY_SUMMARY:a:2026-10-05", null), "테스트서버", KRW);

        assertThat(m.title()).isEqualTo("주간 요약");
        assertThat(m.description()).isEqualTo("지난주 비용 ₩9,085 · 전주와 비슷해요");
        assertThat(m.tone()).isEqualTo(Tone.INFO);
        assertThat(m.url()).isEqualTo("https://www.cloud-cost.cloud/notifications");
    }

    @Test
    void 숫자_기록이_없는_이전_알림은_문구를_설명으로_쓰고_색은_발생_키로_정한다() {
        Message m = service.message(notification(NotificationType.BUDGET, "운영의 이번 달 예상 비용이 예산의 100%를 넘었어요",
            "BUDGET:a:2026-10:100", null), "운영", KRW);

        assertThat(m.description()).isEqualTo("운영의 이번 달 예상 비용이 예산의 100%를 넘었어요");
        assertThat(m.tone()).isEqualTo(Tone.DANGER);
        assertThat(m.fields()).containsExactly(new Field("연결", "운영"));
    }

    @Test
    void 서비스_주소가_없으면_링크를_걸지_않는다() {
        NotificationDispatchService noUrl = new NotificationDispatchService(null, null, null, Clock.systemUTC(), " ");
        assertThat(noUrl.message(notification(NotificationType.WEEKLY_SUMMARY, "x", "k", null), "a", KRW).url()).isNull();
    }
}
