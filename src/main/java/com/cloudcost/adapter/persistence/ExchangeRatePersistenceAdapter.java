package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.ExchangeRateJpa;
import com.cloudcost.application.port.ExchangeRateRepository;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ExchangeRatePersistenceAdapter implements ExchangeRateRepository {

    private static final String USD = "USD";
    private static final String KRW = "KRW";

    private final ExchangeRateJpa rates;

    ExchangeRatePersistenceAdapter(ExchangeRateJpa rates) {
        this.rates = rates;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExchangeRate> latestUsdToKrw() {
        return rates.findFirstByBaseAndQuoteOrderByFetchedAtDesc(USD, KRW)
                .map(e -> new ExchangeRate(e.rate, e.rateDate, e.source, e.fetchedAt));
    }

    @Override
    @Transactional
    public void save(ExchangeRate rate) {
        ExchangeRateEntity e = rates.findByBaseAndQuoteAndRateDate(USD, KRW, rate.rateDate())
                .orElseGet(ExchangeRateEntity::new);
        if (e.id == null) {
            e.id = UUID.randomUUID();
        }
        e.base = USD;
        e.quote = KRW;
        e.rate = rate.usdToKrw();
        e.rateDate = rate.rateDate();
        e.source = rate.source();
        e.fetchedAt = rate.fetchedAt();
        rates.save(e);
    }
}
