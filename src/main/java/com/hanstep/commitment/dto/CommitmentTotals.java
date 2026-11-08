package com.hanstep.commitment.dto;

import com.hanstep.common.dto.Money;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 유형별 합계(계산값, 저장하지 않음).
 * 고정생활비 = RENT·UTILITY·TELECOM·LIVING_MINIMUM·OTHER. MANDATORY_SAVING 은 차감 합계에 넣지 않는다.
 */
@Schema(description = "유형별 합계(계산값). MANDATORY_SAVING 은 deductibleTotal 에 포함하지 않음")
public record CommitmentTotals(
        @Schema(description = "고정생활비 합계(RENT·UTILITY·TELECOM·LIVING_MINIMUM·OTHER)",
                example = "{\"amount\": 520000, \"currency\": \"KRW\"}")
        Money fixedLiving,

        @Schema(description = "송금 합계(REMITTANCE)", example = "{\"amount\": 700000, \"currency\": \"KRW\"}")
        Money remittance,

        @Schema(description = "변동지출 합계(VARIABLE_EXPENSE)", example = "{\"amount\": 450000, \"currency\": \"KRW\"}")
        Money variableExpense,

        @Schema(description = "의무 저축 합계(MANDATORY_SAVING). 여유자금에서 차감하지 않음",
                example = "{\"amount\": 0, \"currency\": \"KRW\"}")
        Money mandatorySaving,

        @Schema(description = "여유자금 계산 때 빼는 합계 = fixedLiving + remittance + variableExpense",
                example = "{\"amount\": 1670000, \"currency\": \"KRW\"}")
        Money deductibleTotal
) {
}
