package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.application.port.ExchangeRateRepository;
import com.cloudcost.application.port.ExchangeRateSource;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import com.cloudcost.fakes.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 원화(KRW) 기본 조회와 원 ↔ 달러 전환 (ADR-005) */
class CurrencyTest {

    @Nested
    class 환산_규칙 {

        private final DisplayCurrency krw = DisplayCurrency.krw(
                new ExchangeRate(new BigDecimal("1333.09"), LocalDate.of(2026, 10, 12), "유럽중앙은행 기준환율", Instant.EPOCH));

        @Test
        void 금액은_원_단위_단가는_0점1원_단위로_반올림() {
            assertThat(krw.cost(new BigDecimal("0.0572"))).isEqualByComparingTo("76");
            assertThat(krw.cost(new BigDecimal("3.4944"))).isEqualByComparingTo("4658");
            assertThat(krw.hourlyRate(new BigDecimal("0.026"))).isEqualByComparingTo("34.7");
        }

        @Test
        void 단가가_없는_금액은_원화에서도_null() {
            assertThat(krw.cost(null)).isNull();
            assertThat(krw.hourlyRate(null)).isNull();
        }

        @Test
        void 달러는_센트_단위_단가는_소수_넷째_자리() {
            DisplayCurrency usd = DisplayCurrency.usd();
            assertThat(usd.cost(new BigDecimal("0.0572"))).isEqualByComparingTo("0.06");
            assertThat(usd.hourlyRate(new BigDecimal("0.026"))).isEqualByComparingTo("0.0260");
            assertThat(usd.conversionNote()).isNull();
        }

        @Test
        void 환산_기준을_안내한다() {
            assertThat(krw.conversionNote()).contains("2026-10-12").contains("1,333.09원").contains("유럽중앙은행");
        }
    }

    @Nested
    class 환율_캐시와_실패 {

        private final MutableClock clock = new MutableClock(Instant.parse("2026-10-13T00:00:00Z"));
        private Optional<ExchangeRate> stored = Optional.empty();
        private int fetches;
        private boolean failing;

        private final ExchangeRateRepository repo = new ExchangeRateRepository() {
            @Override
            public Optional<ExchangeRate> latestUsdToKrw() {
                return stored;
            }

            @Override
            public void save(ExchangeRate rate) {
                stored = Optional.of(rate);
            }
        };

        private final ExchangeRateSource source = () -> {
            fetches++;
            if (failing) {
                throw new ExchangeRateSource.ExchangeRateUnavailableException("down");
            }
            return new ExchangeRate(new BigDecimal("1333.09"), LocalDate.of(2026, 10, 12), "ECB", Instant.EPOCH);
        };

        private final CurrencyService service = new CurrencyService(source, repo, clock, Duration.ofHours(6));

        @Test
        void 기본은_원화이고_6시간_동안은_저장한_환율을_쓴다() {
            assertThat(service.resolve(Currency.KRW).display().currency()).isEqualTo(Currency.KRW);
            clock.advance(Duration.ofHours(5));
            service.resolve(Currency.KRW);
            assertThat(fetches).isEqualTo(1);
            clock.advance(Duration.ofHours(2));
            service.resolve(Currency.KRW);
            assertThat(fetches).isEqualTo(2);
        }

        @Test
        void 환율_API가_실패하면_마지막_환율을_쓰고_안내한다() {
            service.resolve(Currency.KRW);
            clock.advance(Duration.ofDays(1));
            failing = true;
            CurrencyService.Resolved resolved = service.resolve(Currency.KRW);
            assertThat(resolved.display().currency()).isEqualTo(Currency.KRW);
            assertThat(resolved.warnings()).singleElement().asString().contains("2026-10-12 환율로 환산");
        }

        @Test
        void 환율을_한_번도_받지_못했으면_달러로_응답한다() {
            failing = true;
            CurrencyService.Resolved resolved = service.resolve(Currency.KRW);
            assertThat(resolved.display().currency()).isEqualTo(Currency.USD);
            assertThat(resolved.warnings()).contains("환율을 가져오지 못해 달러(USD)로 표시합니다.");
        }

        @Test
        void 달러로_조회하면_환율을_부르지_않는다() {
            assertThat(service.resolve(Currency.USD).display().currency()).isEqualTo(Currency.USD);
            assertThat(fetches).isZero();
        }
    }
}
