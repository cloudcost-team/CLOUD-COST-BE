package com.cloudcost.adapter.fx;

import com.cloudcost.application.port.ExchangeRateSource;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Frankfurter (유럽중앙은행 기준환율, 무료·키 불필요). 영업일마다 갱신되고 주말·공휴일에는 직전 영업일 값을 준다.
 * 사용자 데이터는 보내지 않는다 (통화 코드만 요청).
 */
@Component
class FrankfurterExchangeRates implements ExchangeRateSource {

    static final String SOURCE = "유럽중앙은행 기준환율";

    private final URI url;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    FrankfurterExchangeRates(
            @Value("${cci.fx.url:https://api.frankfurter.dev/v1/latest?base=USD&symbols=KRW}") String url,
            ObjectMapper mapper) {
        this.url = URI.create(url);
        this.mapper = mapper;
    }

    @Override
    public ExchangeRate fetchUsdToKrw() {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ExchangeRateUnavailableException("환율 API 응답 " + response.statusCode());
            }
            return parse(response.body(), mapper);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExchangeRateUnavailableException("환율 조회 중단");
        } catch (java.io.IOException e) {
            throw new ExchangeRateUnavailableException("환율 API 연결 실패");
        }
    }

    /** {"amount":1.0,"base":"USD","date":"2026-10-12","rates":{"KRW":1333.09}} */
    static ExchangeRate parse(String body, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode krw = root.path("rates").path("KRW");
            if (!"USD".equals(root.path("base").asText()) || !krw.isNumber()) {
                throw new ExchangeRateUnavailableException("환율 응답 형식이 다릅니다");
            }
            BigDecimal rate = krw.decimalValue();
            // 터무니없는 값이면 쓰지 않는다 (API 오류 방어)
            if (rate.compareTo(BigDecimal.valueOf(100)) < 0 || rate.compareTo(BigDecimal.valueOf(10000)) > 0) {
                throw new ExchangeRateUnavailableException("환율 값이 비정상입니다");
            }
            return new ExchangeRate(rate, LocalDate.parse(root.path("date").asText()), SOURCE, Instant.EPOCH);
        } catch (ExchangeRateUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ExchangeRateUnavailableException("환율 응답을 해석하지 못했습니다");
        }
    }
}
