package com.cloudcost.adapter.awsinternal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Price List GetProducts 응답(priceList: JSON 문자열 목록)에서 온디맨드 시간당 USD 단가를 꺼낸다.
 *
 * <pre>terms.OnDemand.*.priceDimensions.*.{unit:"Hrs", pricePerUnit:{USD:"0.0130000000"}}</pre>
 * 필터가 정확하면 상품은 하나다. 여러 개면 결정적으로 가장 낮은 양수 단가를 쓴다. 0원 항목은 무시한다.
 */
final class PriceListParser {

    private PriceListParser() {
    }

    static Optional<BigDecimal> onDemandHourlyUsd(List<String> priceList, ObjectMapper mapper) {
        BigDecimal best = null;
        for (String item : priceList) {
            JsonNode root;
            try {
                root = mapper.readTree(item);
            } catch (IOException e) {
                continue;
            }
            for (JsonNode term : iterable(root.path("terms").path("OnDemand"))) {
                for (JsonNode dimension : iterable(term.path("priceDimensions"))) {
                    if (!"Hrs".equals(dimension.path("unit").asText())) {
                        continue;
                    }
                    String usd = dimension.path("pricePerUnit").path("USD").asText(null);
                    if (usd == null) {
                        continue;
                    }
                    BigDecimal value;
                    try {
                        value = new BigDecimal(usd);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (value.signum() > 0 && (best == null || value.compareTo(best) < 0)) {
                        best = value;
                    }
                }
            }
        }
        return Optional.ofNullable(best).map(BigDecimal::stripTrailingZeros);
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node::elements;
    }
}
