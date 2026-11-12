package com.hanstep.visa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 감점·제외 판정에 쓰는 자기신고 6문항. 저장하지 않고 계산 요청 때만 받아 input_snapshot 에만 남긴다. */
@Schema(description = "감점·제외 자기신고 6문항(신청일 기준 최근 10년). 저장하지 않음. 모르는 값은 null(AI 가 재질의 목록으로 돌려줌)")
public record LegalDeclaration(
        @Schema(description = "벌금 100만원 미만의 형 횟수(0~99)", example = "0", nullable = true)
        @Min(0) @Max(99)
        Integer finesUnder1mCount,

        @Schema(description = "벌금 100만원 이상의 형 여부", example = "false", nullable = true)
        Boolean hasFine1mOrMore,

        @Schema(description = "조세 체납으로 체류허가 제한을 받은 횟수(0~99)", example = "0", nullable = true)
        @Min(0) @Max(99)
        Integer taxStayRestrictionCount,

        @Schema(description = "출입국관리법 위반 횟수(0~99)", example = "0", nullable = true)
        @Min(0) @Max(99)
        Integer immigrationViolationCount,

        @Schema(description = "3개월 이상 불법체류 경력 여부", example = "false", nullable = true)
        Boolean hasOverstay3mOrMore,

        @Schema(description = "현재 조세 체납 여부(완납하면 false)", example = "false", nullable = true)
        Boolean hasCurrentTaxArrears
) {
}
