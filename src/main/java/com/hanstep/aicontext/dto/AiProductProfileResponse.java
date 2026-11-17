package com.hanstep.aicontext.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * INT-19 응답. 챗봇 상품 추천 도구(recommend_savings_products)의 조건 판정용.
 * LLM 에 넘어가는 데이터라 생년월일은 넣지 않고 asOf 기준 만 나이만 준다.
 */
@Schema(description = "AI 상품 추천용 프로필(INT-19)")
public record AiProductProfileResponse(
        @Schema(description = "현재 비자 종류 코드. 비자 프로필이 없으면 null", example = "E-9", nullable = true)
        String visaTypeCode,

        @Schema(description = "체류 만료일. 비자 프로필이 없거나 값이 없으면 null", example = "2027-10-04", nullable = true)
        LocalDate stayExpiresOn,

        @Schema(description = "asOf 기준 만 나이. 생년월일이 없으면 null", example = "31", nullable = true)
        Integer age,

        @Schema(description = "계산 기준일(응답을 만든 날의 Asia/Seoul 날짜)", example = "2026-11-17")
        LocalDate asOf
) {
}
