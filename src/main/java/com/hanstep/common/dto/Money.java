package com.hanstep.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 금액 공통 형식 {"amount": 정수, "currency": "KRW"}.
 * DB 는 DECIMAL(18,2) 이지만 API 는 원 단위 정수만 주고받음.
 */
@Schema(description = "금액. amount 는 정수(원 단위), currency 는 ISO 4217 코드")
public record Money(
        @Schema(description = "금액(정수)", example = "2350000")
        @NotNull
        @PositiveOrZero
        Long amount,

        @Schema(description = "통화(ISO 4217)", example = "KRW")
        @NotNull
        @Pattern(regexp = "^[A-Z]{3}$")
        String currency
) {

    public static final String KRW = "KRW";

    public static Money krw(long amount) {
        return new Money(amount, KRW);
    }
}
