package com.cloudcost.fakes;

import com.cloudcost.application.port.NotificationChannelGateway;
import com.cloudcost.domain.alert.NotificationChannel;
import java.util.ArrayList;
import java.util.List;

/** 실제 Discord로 보내지 않고 보낸 내용을 기록한다. next를 바꾸면 그 결과를 돌려준다. */
public class FakeNotificationChannelGateway implements NotificationChannelGateway {

    public record Sent(NotificationChannel channel, Message message) {}

    public final List<Sent> sent = new ArrayList<>();
    public SendResult next = SendResult.success();

    @Override
    public synchronized SendResult send(NotificationChannel channel, Message message) {
        sent.add(new Sent(channel, message));
        return next;
    }

    public synchronized void reset() {
        sent.clear();
        next = SendResult.success();
    }
}
