package com.cloudcost.fakes;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.port.PriceCatalog;
import com.cloudcost.domain.cost.PriceQuery;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class FakePriceCatalog implements PriceCatalog {

    public final Map<String, BigDecimal> hourlyByType = new HashMap<>();
    public AwsAccessException error;
    public int calls;

    public void reset() {
        hourlyByType.clear();
        error = null;
        calls = 0;
    }

    @Override
    public Optional<BigDecimal> findOnDemandHourlyUsd(PriceQuery query) {
        calls++;
        if (error != null) {
            throw error;
        }
        return Optional.ofNullable(hourlyByType.get(query.instanceType()));
    }
}
