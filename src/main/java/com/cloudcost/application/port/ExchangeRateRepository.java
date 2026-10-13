package com.cloudcost.application.port;

import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import java.util.Optional;

/** 환율 캐시. 공개 데이터라 owner가 없다. */
public interface ExchangeRateRepository {

    /** 가장 최근에 받은 USD→KRW 환율 */
    Optional<ExchangeRate> latestUsdToKrw();

    /** 같은 기준일이면 갱신한다. */
    void save(ExchangeRate rate);
}
