package com.hanstep.fx.dto;

import com.hanstep.fx.repository.FxAlertRepository.JudgeRate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Schema(description = "최신 실데이터 값으로 ACTIVE 알림을 다시 판정한 결과(내부 관리용)")
public record FxAlertEvaluateResponse(
        @Schema(description = "'알림 뒤에 저장된 행' 규칙을 빼고 판정했는지(시연용)", example = "false")
        boolean ignoreCreatedAt,

        @Schema(description = "이번에 TRIGGERED 로 바꾼 알림 수", example = "1")
        int triggered,

        @Schema(description = "통화별 결과")
        List<CurrencyResult> currencies
) {

    @Schema(name = "FxAlertEvaluateCurrency", description = "통화 1개의 판정에 쓴 환율 행과 알림별 결과")
    public record CurrencyResult(
            @Schema(description = "통화", example = "PHP")
            String currency,

            @Schema(description = "판정에 쓴 환율 행(exchange_rates.id). 실데이터가 없으면 null", nullable = true)
            Long rateId,

            @Schema(description = "그 행의 rate_at(+09:00)", nullable = true)
            OffsetDateTime rateAt,

            @Schema(description = "그 행을 처음 저장한 시각(exchange_rates.created_at, +09:00). 알림보다 늦어야 판정 대상",
                    nullable = true)
            OffsetDateTime savedAt,

            @Schema(description = "매매기준율(base_rate)", nullable = true)
            BigDecimal baseRate,

            @Schema(description = "송금 보낼 때 환율(send_rate). 원천에 없으면 null", nullable = true)
            BigDecimal sendRate,

            @Schema(description = "그 통화 ACTIVE 알림별 결과")
            List<AlertResult> alerts
    ) {

        public static CurrencyResult of(String currency, JudgeRate rate, List<AlertResult> alerts) {
            if (rate == null) {
                return new CurrencyResult(currency, null, null, null, null, null, alerts);
            }
            return new CurrencyResult(currency, rate.id(), kst(rate.rateAt()), kst(rate.createdAt()),
                    plain(rate.baseRate()), plain(rate.sendRate()), alerts);
        }
    }

    @Schema(name = "FxAlertEvaluateAlert", description = "알림 1건의 판정 결과")
    public record AlertResult(
            @Schema(description = "알림 ID", example = "12")
            long alertId,

            @Schema(description = "TRIGGERED: 이번에 바꿈 / NOT_REACHED: 목표에 닿지 않음 / NO_RATE_VALUE: 그 행에 rateType 값이 없음 "
                    + "/ RATE_BEFORE_CREATED: 환율 행이 알림보다 먼저 저장됨 / NO_RATE: 실데이터 없음 "
                    + "/ ALREADY_CHANGED: 그사이 다른 판정·삭제가 먼저 바꿈", example = "TRIGGERED")
            String result,

            @Schema(description = "도달 판정에 쓴 값(TRIGGERED·ALREADY_CHANGED 일 때, 끝자리 0 제거)", nullable = true)
            BigDecimal value
    ) {

        public static AlertResult of(long alertId, String result, BigDecimal value) {
            return new AlertResult(alertId, result, plain(value));
        }
    }

    private static OffsetDateTime kst(Instant at) {
        return at.atOffset(ZoneOffset.ofHours(9)).truncatedTo(ChronoUnit.SECONDS);
    }

    /** 22.1000 → 22.1, 1350.0000 → 1350(지수 표기 없음). FxAlertResponse 와 같은 표기. */
    private static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
