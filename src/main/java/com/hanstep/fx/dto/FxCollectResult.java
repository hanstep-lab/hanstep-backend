package com.hanstep.fx.dto;

import com.hanstep.fx.domain.FxSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "원천 1개의 수집·백필 결과")
public record FxCollectResult(
        @Schema(description = "출처", example = "KOREAEXIM")
        FxSource source,

        @Schema(description = "MARKET_REF 의 원천 이름(frankfurter-ecb). KOREAEXIM 은 null", nullable = true)
        String provider,

        @Schema(description = "OK / PARTIAL(일부 실패 또는 호출 상한에서 멈춤) / FAILED(성공한 호출 없음) / "
                + "SKIPPED(이 원천으로 받을 통화가 설정에 없음)", example = "OK")
        String status,

        @Schema(description = "원천 호출 수", example = "261")
        int calls,

        @Schema(description = "값이 없던 호출 수(고시 전·공휴일 등 빈 응답, 실패 아님)", example = "12")
        int emptyResponses,

        @Schema(description = "통화별 저장 건수")
        List<CurrencyCount> currencies,

        @Schema(description = "실패한 호출(날짜·이유). 이유에 인증키는 나오지 않는다")
        List<Failure> failures,

        @Schema(description = "호출 상한·인증키 오류·일일 한도로 멈췄을 때 다음에 from 으로 넘길 날짜. 끝까지 했으면 null",
                nullable = true)
        LocalDate nextFrom
) {

    @Schema(name = "FxCollectCurrencyCount", description = "통화 1개의 저장 결과")
    public record CurrencyCount(
            @Schema(example = "USD") String currency,
            @Schema(description = "새로 넣은 행", example = "249") int inserted,
            @Schema(description = "값이 바뀌어 고친 행", example = "0") int updated,
            @Schema(description = "같은 rate_date·같은 값이라 값을 그대로 둔 행. 1시간 수집이면 fetched_at(마지막 확인 시각)만 "
                    + "고치고, 백필은 아무것도 쓰지 않는다", example = "0") int unchanged,
            @Schema(description = "같은 키에 Mock 행(04 시드)이 있어 건드리지 않은 행. 운영은 Mock 정리 SQL 뒤라 0",
                    example = "0") int keptMock,
            @Schema(description = "검증 실패로 저장하지 않은 값(숫자 아님·0 이하·소수 5자리 이상·응답에 없는 통화)",
                    example = "0") int rejected) {
    }

    @Schema(name = "FxCollectFailure", description = "실패한 호출")
    public record Failure(
            @Schema(description = "수출입은행은 조회 날짜, 그 밖에는 null", nullable = true) LocalDate date,
            @Schema(example = "HTTP 503") String reason) {
    }
}
