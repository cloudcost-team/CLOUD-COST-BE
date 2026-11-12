package com.cloudcost.application;

import com.cloudcost.application.port.NotificationChannelGateway;
import com.cloudcost.application.port.NotificationChannelGateway.Field;
import com.cloudcost.application.port.NotificationChannelGateway.Message;
import com.cloudcost.application.port.NotificationChannelGateway.SendResult;
import com.cloudcost.application.port.NotificationChannelGateway.Tone;
import com.cloudcost.application.port.NotificationDeliveryRepository;
import com.cloudcost.application.port.NotificationDeliveryRepository.Due;
import com.cloudcost.application.port.NotificationDeliveryRepository.Undelivered;
import com.cloudcost.domain.alert.AlertFacts;
import com.cloudcost.domain.alert.AlertRules;
import com.cloudcost.domain.alert.Delivery;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 외부 채널 발송 (ADR-019, Outbox). 알림 평가는 알림 행만 저장하고, 이 서비스가 따로 돌면서
 * ① 켜진 채널마다 아직 발송 기록이 없는 알림의 기록(PENDING)을 만들고 ② 차례가 된 것을 보내 결과를 남긴다.
 * 발송이 실패해도 알림 행은 그대로라 서비스 안 알림창에는 남고, 실패한 발송은 간격을 늘려 다시 보낸다.
 */
public class NotificationDispatchService {

    /** 채널 등록 전이나 너무 오래된 알림은 보내지 않는다 */
    static final Duration MAX_AGE = Duration.ofDays(3);
    static final int BATCH = 50;
    /** 금액이 청구서가 아니라는 표시 */
    static final String COST_FOOTER = "사용 시간 × 온디맨드 단가 기준";

    /** 한 번 돌 때 만든·보낸 수 */
    public record Result(int queued, int sent, int failed) {}

    private final NotificationDeliveryRepository deliveries;
    private final NotificationChannelGateway gateway;
    private final CurrencyService currencies;
    private final Clock clock;
    private final String appUrl;

    /** @param appUrl 메시지 제목에 걸 서비스 주소 (비어 있으면 링크를 걸지 않는다) */
    public NotificationDispatchService(
        NotificationDeliveryRepository deliveries, NotificationChannelGateway gateway, CurrencyService currencies,
        Clock clock, String appUrl) {
        this.deliveries = deliveries;
        this.gateway = gateway;
        this.currencies = currencies;
        this.clock = clock;
        this.appUrl = appUrl == null ? "" : appUrl.trim().replaceAll("/+$", "");
    }

    public Result dispatch() {
        Instant now = clock.instant();
        int queued = 0;
        for (Undelivered u : deliveries.findUndelivered(now.minus(MAX_AGE), BATCH)) {
            if (deliveries.insertIfAbsent(
                Delivery.pending(UUID.randomUUID(), u.ownerId(), u.notificationId(), u.channelId(), now))) {
                queued++;
            }
        }
        int sent = 0;
        int failed = 0;
        List<Due> due = deliveries.findDue(now, BATCH);
        // 금액은 보낼 때의 환율로 원화 환산한다 (환율을 못 받으면 USD). 한 번 돌 때 한 번만 구한다
        DisplayCurrency display = due.isEmpty() ? null : currencies.resolve(Currency.KRW).display();
        for (Due d : due) {
            SendResult result = gateway.send(d.channel(), message(d.notification(), d.accountName(), display));
            Instant at = clock.instant();
            if (result.ok()) {
                deliveries.save(d.delivery().sent(at));
                sent++;
            } else {
                deliveries.save(d.delivery().failed(result.reason(), result.retryable(), at));
                failed++;
            }
        }
        return new Result(queued, sent, failed);
    }

    /**
     * 알림 1건 → 채널 메시지 (Discord embed). 서비스 안 알림창의 message는 그대로 두고, 채널용으로 제목·필드를 다시 짠다.
     * 숫자(facts)가 없는 이전 알림은 message를 설명으로 쓴다.
     *
     * @param accountName 연결 별명 (없으면 null → "이름 없는 연결")
     */
    Message message(Notification n, String accountName, DisplayCurrency display) {
        String name = accountName == null ? NotificationService.UNNAMED : accountName;
        AlertFacts f = n.facts();
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("연결", name));
        return switch (n.type()) {
            case BUDGET -> {
                Integer threshold = f.thresholdPercent() != null ? f.thresholdPercent() : thresholdFromKey(n.eventKey());
                if (f.thresholdPercent() == null) {
                    yield new Message("예산 알림", n.message(), budgetTone(threshold), fields, COST_FOOTER, link("/dashboard"));
                }
                fields.add(new Field("월말 예상 비용", money(f.forecastUsd(), display)));
                fields.add(new Field("예산 금액", money(f.budgetUsd(), display)));
                yield new Message("예산 " + threshold + "% 넘음",
                    name + "의 이번 달 월말 예상 비용이 예산의 " + threshold + "%를 넘었어요.",
                    budgetTone(threshold), fields, COST_FOOTER, link("/dashboard"));
            }
            case SPIKE -> {
                if (f.spikeDate() == null) {
                    yield new Message("비용 급증", n.message(), Tone.WARNING, fields, COST_FOOTER, link("/dashboard"));
                }
                String date = f.spikeDate().getMonthValue() + "월 " + f.spikeDate().getDayOfMonth() + "일";
                fields.add(new Field("날짜", date));
                fields.add(new Field("평소 대비", f.spikeRatio().toPlainString() + "배"));
                if (f.causeName() != null) {
                    fields.add(new Field("원인 서버", f.causeName()));
                }
                yield new Message("비용 급증", name + "의 " + date + " 비용이 평소의 " + f.spikeRatio().toPlainString()
                    + "배예요.", Tone.WARNING, fields, COST_FOOTER, link("/dashboard"));
            }
            case WEEKLY_SUMMARY -> new Message("주간 요약", n.message(), Tone.INFO, fields, COST_FOOTER,
                link("/notifications"));
        };
    }

    static Tone budgetTone(Integer threshold) {
        return threshold != null && threshold >= 100 ? Tone.DANGER : Tone.WARNING;
    }

    /** BUDGET:{계정}:{월}:{기준} → 기준 (숫자 기록이 없는 이전 알림의 색을 정할 때) */
    private static Integer thresholdFromKey(String eventKey) {
        try {
            return Integer.valueOf(eventKey.substring(eventKey.lastIndexOf(':') + 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String money(BigDecimal usd, DisplayCurrency display) {
        return usd == null ? null : AlertRules.money(usd, display == null ? DisplayCurrency.usd() : display);
    }

    private String link(String path) {
        return appUrl.isEmpty() ? null : appUrl + path;
    }
}
