package com.cloudcost.domain.budget;

import com.cloudcost.domain.DomainValidationException;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 연결 1건의 월 예산. 사용자가 입력한 통화 그대로 보관한다 (원화로 정한 예산이 환율에 따라 바뀌지 않게).
 * 비교할 때만 USD로 바꾼다.
 */
public record MonthlyBudget(BigDecimal amount, Currency currency) {

    public static final String INVALID = "VALIDATION_ERROR";
    /** 1조 (원·달러 공통). 오타로 들어온 비현실적인 값을 막는다 */
    public static final BigDecimal MAX = new BigDecimal("1000000000000");

    public MonthlyBudget {
        if (currency == null) {
            throw new DomainValidationException(INVALID, "예산 통화를 골라 주세요 (KRW 또는 USD).");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new DomainValidationException(INVALID, "월 예산은 0보다 큰 숫자로 입력해 주세요 (예: 400000).");
        }
        if (amount.compareTo(MAX) > 0) {
            throw new DomainValidationException(INVALID, "월 예산이 너무 커요. 1조 이하로 입력해 주세요.");
        }
        // 원화는 원 단위, 달러는 센트 단위까지만 쓴다.
        amount = amount.setScale(currency == Currency.KRW ? 0 : 2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new DomainValidationException(INVALID, "월 예산은 0보다 큰 숫자로 입력해 주세요 (예: 400000).");
        }
    }

    /**
     * 비교용 USD 금액. 원화 예산인데 환율이 없으면 null (예산 대비 비율을 계산하지 않는다).
     */
    public BigDecimal toUsd(ExchangeRate rate) {
        if (currency == Currency.USD) {
            return amount;
        }
        if (rate == null) {
            return null;
        }
        return amount.divide(rate.usdToKrw(), 6, RoundingMode.HALF_UP);
    }
}
