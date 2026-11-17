package com.hanstep.credit.domain;

/**
 * 대안신용 모의 사전진단 판정(AI app/credit/alt_score.py AltCreditDecision). 실제 대출 승인 여부가 아니다.
 * ELIGIBLE=모의 기준상 대상·조건 충족, NOT_ELIGIBLE=대상 아님(소득·근로 자격·급여 입금), CONDITION_NOT_MET=대상이지만 조건 미충족.
 */
public enum AltCreditDecision {
    ELIGIBLE, NOT_ELIGIBLE, CONDITION_NOT_MET
}
