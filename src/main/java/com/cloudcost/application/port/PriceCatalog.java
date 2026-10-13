package com.cloudcost.application.port;

import com.cloudcost.domain.cost.PriceQuery;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * 온디맨드 단가 조회 (구현: adapter/awsinternal, 우리 계정의 Price List). 고객 계정 권한을 쓰지 않는다.
 */
public interface PriceCatalog {

    /**
     * @return 단가가 없으면 empty. 조회 자체가 실패하면 AwsAccessException.
     */
    Optional<BigDecimal> findOnDemandHourlyUsd(PriceQuery query);
}
