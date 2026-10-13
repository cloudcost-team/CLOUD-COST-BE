package com.cloudcost.domain.usage;

import com.cloudcost.domain.DomainValidationException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** 조회 기간 [from, to] (양 끝 포함, zone 기준 날짜). 최대 455일. */
public record DateRange(LocalDate from, LocalDate to) {

    public static final long MAX_DAYS = 455;

    public DateRange {
        if (from == null || to == null) {
            throw new DomainValidationException("INVALID_PERIOD", "조회 기간이 비어 있습니다.");
        }
        if (from.isAfter(to)) {
            throw new DomainValidationException("INVALID_PERIOD", "from은 to보다 늦을 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new DomainValidationException("INVALID_PERIOD", "조회 기간은 최대 " + MAX_DAYS + "일입니다.");
        }
    }

    /** 값이 없으면 기본값을 쓴다. 기간을 넣지 않아도 바로 결과를 보여 주기 위해서다. */
    public static DateRange resolve(LocalDate from, LocalDate to, LocalDate defaultFrom, LocalDate defaultTo) {
        return new DateRange(from == null ? defaultFrom : from, to == null ? defaultTo : to);
    }

    public Instant startInclusive(ZoneId zone) {
        return from.atStartOfDay(zone).toInstant();
    }

    public Instant endExclusive(ZoneId zone) {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
