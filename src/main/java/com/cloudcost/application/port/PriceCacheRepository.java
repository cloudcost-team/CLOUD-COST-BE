package com.cloudcost.application.port;

import com.cloudcost.domain.cost.PriceQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * 공개 단가 캐시. 사용자 데이터가 아니므로 owner가 없는 유일한 저장소다.
 */
public interface PriceCacheRepository {

    Optional<CachedPrice> find(PriceQuery query);

    void save(PriceQuery query, BigDecimal usdPerHour, Instant fetchedAt);

    /**
     * @param usdPerHour 단가가 없다는 결과도 저장한다 (null)
     */
    record CachedPrice(BigDecimal usdPerHour, Instant fetchedAt) {
    }
}
