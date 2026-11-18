package com.hanstep.fx.service;

import com.hanstep.fx.domain.FxSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 새 환율을 저장한 직후 발행한다(새 행이거나 값이 바뀐 행만. 같은 값 재수집은 발행하지 않음).
 * 원천 1번 실행(1시간 수집 또는 백필)마다 1건. 목표 환율 알림 도달 판정(작업 4)이 여기에 붙는다.
 * backfill 이면 지난 기간을 채운 것이라 도달 판정에서 거를 수 있다. 리스너 오류는 저장에 영향을 주지 않는다.
 */
public record ExchangeRatesSavedEvent(FxSource source, boolean backfill, List<SavedRate> rates) {

    /** exchange_rates.id 와 키. 시각은 UTC. */
    public record SavedRate(long rateId, String currency, LocalDate rateDate, Instant rateAt) {
    }
}
