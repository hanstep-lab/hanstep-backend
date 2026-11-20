package com.hanstep.finance.dto;

import com.hanstep.common.dto.Money;
import com.hanstep.finance.domain.BalanceSource;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 월 수지 요약(MonthlyBalanceService 계산값). ONB-04 balance, INT-06 balance, PRD-02 여유자금이 같은 값을 쓴다(#151).
 * 여유자금 = 월소득 − 고정생활비 − 송금 − 변동지출. MANDATORY_SAVING 은 빼지 않음.
 */
@Schema(name = "MonthlyBalance", description = "월 수지 요약(계산값). 월소득은 FIN-05, 고정생활비·송금·변동지출은 직접 입력한 "
        + "묶음이 있으면 입력 합계, 없으면 FIN-05. spare = avgMonthlyIncome − fixedLiving − remittance − variableExpense")
public record MonthlyBalanceResponse(
        @Schema(description = "월평균 소득(Mock 거래 기준, FIN-05)",
                example = "{\"amount\": 2350000, \"currency\": \"KRW\"}")
        Money avgMonthlyIncome,

        @Schema(description = "월 고정생활비. RENT·UTILITY·TELECOM·LIVING_MINIMUM·OTHER 를 입력했으면 입력 합계, "
                + "아니면 Mock 거래 기준 월평균(주거·공과금·통신·대출 상환, FIN-05)",
                example = "{\"amount\": 520000, \"currency\": \"KRW\"}")
        Money fixedLiving,

        @Schema(description = "fixedLiving 근거", example = "USER_INPUT")
        BalanceSource fixedLivingSource,

        @Schema(description = "월 송금. REMITTANCE 를 입력했으면 입력 합계, 아니면 Mock 거래 기준 월평균(FIN-05)",
                example = "{\"amount\": 700000, \"currency\": \"KRW\"}")
        Money remittance,

        @Schema(description = "remittance 근거", example = "USER_INPUT")
        BalanceSource remittanceSource,

        @Schema(description = "월 변동지출. VARIABLE_EXPENSE 를 입력했으면 입력 합계, 아니면 Mock 거래 기준 월평균(FIN-05)",
                example = "{\"amount\": 450000, \"currency\": \"KRW\"}")
        Money variableExpense,

        @Schema(description = "variableExpense 근거", example = "USER_INPUT")
        BalanceSource variableExpenseSource,

        @Schema(description = "여유자금. 음수 가능(지출이 소득보다 많음). "
                + "공통 Money 스키마의 amount 최소 0 은 요청 검증용이라 이 필드에는 해당하지 않음",
                example = "{\"amount\": 680000, \"currency\": \"KRW\"}")
        Money spare,

        @Schema(description = "Mock 데이터 기반이면 true(월소득·거래 평균을 낸 적재 배치 기준)", example = "true")
        boolean isMock
) {
}
