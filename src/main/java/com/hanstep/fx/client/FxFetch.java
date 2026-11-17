package com.hanstep.fx.client;

import java.util.List;

/**
 * 원천 호출 1번의 결과. rates 가 비었으면 "새 값 없음"(고시 전·비영업일)이고 실패가 아니다.
 * rejections 는 저장하지 않은 항목과 이유(숫자 아님·0 이하·소수 5자리 이상, 응답에 없는 통화).
 */
public record FxFetch(List<FxRate> rates, List<Rejection> rejections) {

    public static final FxFetch EMPTY = new FxFetch(List.of(), List.of());

    public record Rejection(String currency, String reason) {
    }
}
