package com.cloudcost.application.port;

import com.cloudcost.domain.alert.NotificationChannel;
import java.util.List;

/** 외부 알림 채널로 메시지 1건 보내기 (구현: adapter/notify). */
public interface NotificationChannelGateway {

    SendResult send(NotificationChannel channel, Message message);

    /** 메시지 색 (Discord embed 왼쪽 막대) */
    enum Tone {
        /** 예산 100%·120% */
        DANGER,
        /** 예산 80%·비용 급증 */
        WARNING,
        /** 주간 요약·테스트 */
        INFO
    }

    /** 이름: 값 한 줄 */
    record Field(String name, String value) {}

    /**
     * 채널에 보낼 메시지. 채널 형식(Discord embed 등)으로 바꾸는 건 어댑터가 한다.
     *
     * @param url    제목에 걸 서비스 주소. 없으면 null (본문에 URL을 쓰지 않는다 — 미리보기 카드가 펼쳐진다)
     * @param footer 맨 아래 작은 글씨. 없으면 null
     */
    record Message(String title, String description, Tone tone, List<Field> fields, String footer, String url) {

        public Message {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

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
