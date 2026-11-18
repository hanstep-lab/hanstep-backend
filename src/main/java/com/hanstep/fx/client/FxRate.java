package com.hanstep.fx.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 원천에서 받은 환율 1건(exchange_rates 1행). 검증(0보다 큼, 소수 4자리까지)을 통과한 값만 담는다.
 * 환율은 unitAmount 외화당 원화. rateAt 은 UTC. sendRate·receiveRate 는 KOREAEXIM 만 있고 MARKET_REF 는 null.
 */
public record FxRate(String currency, int unitAmount, LocalDate rateDate, Instant rateAt, BigDecimal baseRate,
                     BigDecimal sendRate, BigDecimal receiveRate) {
}
