package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 공개 환율 캐시 (owner 없음) */
@Entity
@Table(name = "exchange_rates")
class ExchangeRateEntity {

    @Id
    UUID id;

    @Column(nullable = false, length = 3)
    String base;

    @Column(nullable = false, length = 3)
    String quote;

    @Column(nullable = false, precision = 18, scale = 6)
    BigDecimal rate;

    @Column(name = "rate_date", nullable = false)
    LocalDate rateDate;

    @Column(nullable = false, length = 100)
    String source;

    @Column(name = "fetched_at", nullable = false)
    Instant fetchedAt;

    protected ExchangeRateEntity() {
    }
}
