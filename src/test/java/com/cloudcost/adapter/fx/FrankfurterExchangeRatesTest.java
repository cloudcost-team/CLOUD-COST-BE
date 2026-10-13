package com.cloudcost.adapter.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.application.port.ExchangeRateSource.ExchangeRateUnavailableException;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FrankfurterExchangeRatesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void USD_KRW_환율과_기준일을_꺼낸다() {
        ExchangeRate rate = FrankfurterExchangeRates.parse(
                "{\"amount\":1.0,\"base\":\"USD\",\"date\":\"2026-10-12\",\"rates\":{\"KRW\":1333.09}}", mapper);
        assertThat(rate.usdToKrw()).isEqualByComparingTo("1333.09");
        assertThat(rate.rateDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(rate.source()).isEqualTo("유럽중앙은행 기준환율");
    }

    @Test
    void 형식이_다르거나_비정상_값이면_쓰지_않는다() {
        for (String body : new String[] {
                "{\"base\":\"EUR\",\"date\":\"2026-10-12\",\"rates\":{\"KRW\":1500}}",
                "{\"base\":\"USD\",\"date\":\"2026-10-12\",\"rates\":{}}",
                "{\"base\":\"USD\",\"date\":\"2026-10-12\",\"rates\":{\"KRW\":0.5}}",
                "not json"}) {
            assertThatThrownBy(() -> FrankfurterExchangeRates.parse(body, mapper))
                    .isInstanceOf(ExchangeRateUnavailableException.class);
        }
    }
}
