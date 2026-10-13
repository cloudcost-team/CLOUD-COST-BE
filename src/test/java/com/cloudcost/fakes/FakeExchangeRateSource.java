package com.cloudcost.fakes;

import com.cloudcost.application.port.ExchangeRateSource;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 실제 환율 API 대신 쓴다. 기본 1 USD = 1,300원 (2026-10-07). */
public class FakeExchangeRateSource implements ExchangeRateSource {

    public static final BigDecimal RATE = new BigDecimal("1300.00");

    public boolean failing;
    public int calls;

    public void reset() {
        failing = false;
        calls = 0;
    }

    @Override
    public ExchangeRate fetchUsdToKrw() {
        calls++;
        if (failing) {
            throw new ExchangeRateUnavailableException("테스트용 실패");
        }
        return new ExchangeRate(RATE, LocalDate.of(2026, 10, 7), "테스트 환율", Instant.EPOCH);
    }
}
