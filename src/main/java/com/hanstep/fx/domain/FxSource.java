package com.hanstep.fx.domain;

/**
 * 수집기가 쓰는 환율 출처(exchange_rates.source). 수집기는 MOCK 을 만들지 않는다(운영 환율은 실데이터만, #119).
 * KOREAEXIM: 한국수출입은행 현재환율 Open API(매매기준율·송금 보낼 때·받을 때). MARKET_REF: Frankfurter(ECB 기준환율, 매매기준율만).
 */
public enum FxSource {
    KOREAEXIM, MARKET_REF
}
