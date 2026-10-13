package com.cloudcost.domain.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * 응답에 보여 줄 통화. 금액 원본은 항상 USD(AWS 단가 기준)이고, 원화는 환율 하나로 환산한 추정치다.
 *
 * <p>모든 기간의 금액을 같은 환율(최근 기준일)로 바꾼다. 날마다 다른 환율을 쓰면 같은 사용 시간인데 금액이
 * 들쭉날쭉해 보이기 때문이다. 어떤 환율을 썼는지는 응답에 함께 준다.
 *
 * @param rate USD일 때는 null
 */
public record DisplayCurrency(Currency currency, ExchangeRate rate) {

    public enum Currency {
        KRW,
        USD
    }

    /** USD→KRW 환율 1건. */
    public record ExchangeRate(BigDecimal usdToKrw, LocalDate rateDate, String source, Instant fetchedAt) {}

    public static DisplayCurrency usd() {
        return new DisplayCurrency(Currency.USD, null);
    }

    public static DisplayCurrency krw(ExchangeRate rate) {
        return new DisplayCurrency(Currency.KRW, rate);
    }

    /** 기간 합계·일별 금액. KRW는 원 단위, USD는 센트 단위로 반올림. 원본이 null(단가 없음)이면 null. */
    public BigDecimal cost(BigDecimal usd) {
        if (usd == null) {
            return null;
        }
        return currency == Currency.KRW
                ? usd.multiply(rate.usdToKrw()).setScale(0, RoundingMode.HALF_UP)
                : usd.setScale(2, RoundingMode.HALF_UP);
    }

    /** 시간당 단가. KRW는 0.1원, USD는 0.0001달러 단위. */
    public BigDecimal hourlyRate(BigDecimal usdPerHour) {
        if (usdPerHour == null) {
            return null;
        }
        return currency == Currency.KRW
                ? usdPerHour.multiply(rate.usdToKrw()).setScale(1, RoundingMode.HALF_UP)
                : usdPerHour.setScale(4, RoundingMode.HALF_UP);
    }

    /** 응답 notes에 넣을 환산 기준 안내. USD면 null. */
    public String conversionNote() {
        if (currency != Currency.KRW) {
            return null;
        }
        return String.format(Locale.KOREA, "원화 금액은 %s 환율(1 USD = %,.2f원, %s)로 환산한 추정치입니다.",
                rate.rateDate(), rate.usdToKrw(), rate.source());
    }
}
