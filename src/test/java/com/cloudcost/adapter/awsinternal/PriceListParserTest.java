package com.cloudcost.adapter.awsinternal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceListParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String product(String usd) {
        return """
                {"product":{"attributes":{"instanceType":"t3.medium","regionCode":"ap-northeast-2"}},
                 "terms":{"OnDemand":{"SKU.TERM":{"priceDimensions":{"SKU.TERM.RATE":{
                   "unit":"Hrs","pricePerUnit":{"USD":"%s"}}}}}}}
                """.formatted(usd);
    }

    @Test
    void 온디맨드_시간당_USD_단가를_꺼낸다() {
        assertThat(PriceListParser.onDemandHourlyUsd(List.of(product("0.0520000000")), mapper))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.052"));
    }

    @Test
    void 결과가_없거나_0원이면_단가_없음() {
        assertThat(PriceListParser.onDemandHourlyUsd(List.of(), mapper)).isEmpty();
        assertThat(PriceListParser.onDemandHourlyUsd(List.of(product("0.0000000000")), mapper)).isEmpty();
        assertThat(PriceListParser.onDemandHourlyUsd(List.of("not json"), mapper)).isEmpty();
    }

    @Test
    void 여러_상품이면_결정적으로_가장_낮은_양수_단가() {
        assertThat(PriceListParser.onDemandHourlyUsd(List.of(product("0.06"), product("0.052")), mapper))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.052"));
    }
}
