package com.cloudcost.application;

import com.cloudcost.application.port.ExchangeRateRepository;
import com.cloudcost.application.port.ExchangeRateSource;
import com.cloudcost.application.port.ExchangeRateSource.ExchangeRateUnavailableException;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 응답 통화 결정 (ADR-005). 기본은 원화.
 *
 * <p>환율은 외부 API에서 받아 DB에 저장하고 refreshInterval(기본 6시간) 동안 재사용한다.
 * API가 실패하면 마지막으로 저장한 환율을 쓰고 안내한다. 환율이 한 번도 없으면 USD로 응답한다.
 */
public class CurrencyService {

    private final ExchangeRateSource source;
    private final ExchangeRateRepository cache;
    private final Clock clock;
    private final Duration refreshInterval;
    private final Object lock = new Object();

    public CurrencyService(ExchangeRateSource source, ExchangeRateRepository cache, Clock clock, Duration refreshInterval) {
        this.source = source;
        this.cache = cache;
        this.clock = clock;
        this.refreshInterval = refreshInterval;
    }

    /** @param warnings 환율을 못 받은 경우의 안내 */
    public record Resolved(DisplayCurrency display, List<String> warnings) {}

    public Resolved resolve(Currency requested) {
        if (requested == Currency.USD) {
            return new Resolved(DisplayCurrency.usd(), List.of());
        }
        List<String> warnings = new ArrayList<>();
        Optional<ExchangeRate> rate = usdToKrw(warnings);
        if (rate.isEmpty()) {
            warnings.add("환율을 가져오지 못해 달러(USD)로 표시합니다.");
            return new Resolved(DisplayCurrency.usd(), List.copyOf(warnings));
        }
        return new Resolved(DisplayCurrency.krw(rate.get()), List.copyOf(warnings));
    }

    private Optional<ExchangeRate> usdToKrw(List<String> warnings) {
        synchronized (lock) {
            Instant now = clock.instant();
            Optional<ExchangeRate> cached = cache.latestUsdToKrw();
            if (cached.isPresent() && now.isBefore(cached.get().fetchedAt().plus(refreshInterval))) {
                return cached;
            }
            try {
                ExchangeRate fresh = source.fetchUsdToKrw();
                ExchangeRate stamped = new ExchangeRate(fresh.usdToKrw(), fresh.rateDate(), fresh.source(), now);
                cache.save(stamped);
                return Optional.of(stamped);
            } catch (ExchangeRateUnavailableException e) {
                cached.ifPresent(c -> warnings.add(
                        "최신 환율을 가져오지 못해 " + c.rateDate() + " 환율로 환산했습니다."));
                return cached;
            }
        }
    }
}
