package com.hanstep.commitment.domain;

/**
 * 필수 확보액 항목 유형(monthly_commitments.commitment_type).
 *
 * <p>여유자금 계산 시 MANDATORY_SAVING 만 차감하지 않는다. 화면 합계 묶음:
 * 고정생활비 = RENT·UTILITY·TELECOM·LIVING_MINIMUM·OTHER, 송금 = REMITTANCE, 변동지출 = VARIABLE_EXPENSE.
 */
public enum CommitmentType {
    REMITTANCE,
    RENT,
    UTILITY,
    TELECOM,
    MANDATORY_SAVING,
    LIVING_MINIMUM,
    VARIABLE_EXPENSE,
    OTHER
}
