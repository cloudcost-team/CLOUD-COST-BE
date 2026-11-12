package com.cloudcost.domain.alert;

import com.cloudcost.domain.spike.SpikeDetector.Spike;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 알림을 만들 때의 숫자 (외부 채널 메시지의 필드용). 문구(message)와 달리 금액은 USD로 두고, 보낼 때 통화로 바꾼다.
 * 이 기록이 생기기 전에 만든 알림이나 해당 없는 값은 null이다.
 *
 * @param thresholdPercent 예산 알림: 넘은 기준 (80·100·120)
 * @param budgetUsd        예산 알림: 그달 예산
 * @param forecastUsd      예산 알림: 그때의 월말 예상 비용
 * @param spikeDate        급증 알림: 급증일
 * @param spikeRatio       급증 알림: 평소 대비 배수
 * @param causeName        급증 알림: 원인 서버 이름 (Name 태그가 없으면 인스턴스 ID). 없으면 null
 */
public record AlertFacts(
    Integer thresholdPercent,
    BigDecimal budgetUsd,
    BigDecimal forecastUsd,
    LocalDate spikeDate,
    BigDecimal spikeRatio,
    String causeName) {

    public static final AlertFacts NONE = new AlertFacts(null, null, null, null, null, null);

    public static AlertFacts budget(int thresholdPercent, BigDecimal budgetUsd, BigDecimal forecastUsd) {
        return new AlertFacts(thresholdPercent, budgetUsd, forecastUsd, null, null, null);
    }

    public static AlertFacts spike(Spike spike, String causeName) {
        String cause = spike.causeInstanceId() == null ? null
            : causeName == null || causeName.isBlank() ? spike.causeInstanceId() : causeName;
        return new AlertFacts(null, null, null, spike.date(), spike.ratio(), cause);
    }
}
