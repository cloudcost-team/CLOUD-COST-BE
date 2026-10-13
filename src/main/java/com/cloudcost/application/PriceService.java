package com.cloudcost.application;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.port.PriceCacheRepository;
import com.cloudcost.application.port.PriceCacheRepository.CachedPrice;
import com.cloudcost.application.port.PriceCatalog;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.InstancePrice;
import com.cloudcost.domain.cost.PriceQuery;
import com.cloudcost.domain.ec2.Ec2Instance;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 인스턴스별 온디맨드 단가. 우리 계정의 Price List를 쓰고 DB에 캐시한다(기본 7일). 단가 조회가 실패해도 사용 시간 응답은 막지 않는다 (금액만 null + 경고).
 */
public class PriceService {

    private final PriceCatalog catalog;
    private final PriceCacheRepository cache;
    private final Clock clock;
    private final UsageSettings settings;

    public PriceService(PriceCatalog catalog, PriceCacheRepository cache, Clock clock, UsageSettings settings) {
        this.catalog = catalog;
        this.cache = cache;
        this.clock = clock;
        this.settings = settings;
    }

    public record PriceResult(Map<String, InstancePrice> byInstanceId, List<String> warnings) {
    }

    public PriceResult pricesFor(Collection<Ec2Instance> instances) {
        Instant now = clock.instant();
        Map<PriceQuery, Optional<BigDecimal>> resolved = new HashMap<>();
        Map<String, InstancePrice> result = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        boolean catalogAvailable = true;

        for (Ec2Instance instance : instances) {
            Optional<PriceQuery> query = PriceQuery.forInstance(instance);
            if (query.isEmpty()) {
                result.put(instance.instanceId(),
                    InstancePrice.unpriced(instance.instanceId(), instance.spot(), CostNotes.UNSUPPORTED_PLATFORM));
                continue;
            }
            PriceQuery q = query.get();
            Optional<BigDecimal> price = resolved.get(q);
            if (price == null) {
                Optional<CachedPrice> cached = cache.find(q);
                if (cached.isPresent() && isFresh(cached.get(), now)) {
                    price = Optional.ofNullable(cached.get().usdPerHour());
                } else if (catalogAvailable) {
                    try {
                        price = catalog.findOnDemandHourlyUsd(q);
                        cache.save(q, price.orElse(null), now);
                    } catch (AwsAccessException e) {
                        // 같은 원인(예: 서버 자격증명 없음)으로 계속 실패하므로 이번 요청에서는 더 부르지 않는다.
                        catalogAvailable = false;
                        warnings.add("온디맨드 단가 조회 실패: " + e.getMessage());
                        price = cached.map(CachedPrice::usdPerHour);
                    }
                } else {
                    price = cached.map(CachedPrice::usdPerHour);
                }
                resolved.put(q, price);
            }
            result.put(instance.instanceId(),
                InstancePrice.of(instance.instanceId(), price.orElse(null), instance.spot()));
        }
        return new PriceResult(result, warnings);
    }

    private boolean isFresh(CachedPrice cached, Instant now) {
        var ttl = cached.usdPerHour() == null ? settings.missingPriceTtl() : settings.priceTtl();
        return now.isBefore(cached.fetchedAt().plus(ttl));
    }
}
