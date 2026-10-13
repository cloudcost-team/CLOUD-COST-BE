package com.cloudcost.application.port;

import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;

/** 외부 환율 API (구현: adapter/fx). 실패하면 ExchangeRateUnavailableException. */
public interface ExchangeRateSource {

    ExchangeRate fetchUsdToKrw();

    class ExchangeRateUnavailableException extends RuntimeException {
        public ExchangeRateUnavailableException(String message) {
            super(message, null, false, false);
        }
    }
}
