package com.cloudcost.application.port;

import com.cloudcost.domain.alert.NotificationChannel;

/** 외부 알림 채널로 메시지 1건 보내기 (구현: adapter/notify). */
public interface NotificationChannelGateway {

    SendResult send(NotificationChannel channel, String text);

    /**
     * @param reason    실패 사유 (사용자에게 보여 줄 한국어). 성공이면 null
     * @param retryable 다시 보내면 될 수도 있는 실패인지
     */
    record SendResult(boolean ok, String reason, boolean retryable) {

        public static SendResult success() {
            return new SendResult(true, null, false);
        }

        public static SendResult failure(String reason, boolean retryable) {
            return new SendResult(false, reason, retryable);
        }
    }
}
