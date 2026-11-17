package com.hanstep.credit.dto;

import com.hanstep.common.dto.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** SOL 글로벌론 모의 한도(서버 계산, 은행 심사 아님). DSR·타행 대출 심사는 반영하지 않는다. */
@Schema(name = "SolGlobalLoanLimitEstimate", description = "신한걸음 자체 모의 한도 계산. "
        + "Min(연소득 × 80%, 연소득 ÷ 12 × 40% × maxTermMonths, 5,000만 원) − 기존 대출, 만 원 단위 버림(임시)")
public record SolGlobalLoanLimitEstimate(
        @Schema(description = "모의 한도. 조건 미충족(NOT_ELIGIBLE)·대출 가능 기간 6개월 미만(TERM_TOO_SHORT)이면 null",
                nullable = true)
        Money estimatedLimit,

        @Schema(description = "estimatedLimit 가 null 인 이유. 조건 미충족이 우선", nullable = true,
                example = "NOT_ELIGIBLE")
        ReasonCode reasonCode,

        @Schema(description = "계산에 쓴 연소득(원). 소득 정보가 없으면 null", nullable = true)
        Money annualIncome,

        @Schema(description = "연소득 출처(임시). INCOME_RECORD = 가장 최근 VERIFIED 연도 소득(income_records), "
                + "CONTRACT_SALARY = 현 직장 고용계약 연봉(employment_histories), NONE = 없음", example = "INCOME_RECORD")
        IncomeSource incomeSource,

        @Schema(description = "INCOME_RECORD 일 때 귀속 연도. 그 밖은 null", nullable = true, example = "2025")
        Integer incomeYear,

        @Schema(description = "만기일 상한 = 체류 만료월의 전월 말일. 만료일이 없으면 null", nullable = true,
                example = "2027-09-30")
        LocalDate latestMaturityOn,

        @Schema(description = "대출 가능 최대 개월 = asOfDate~latestMaturityOn 의 꽉 찬 달 수(내림), 36 상한. "
                + "만료일이 없으면 null", nullable = true, example = "10")
        Integer maxTermMonths,

        @Schema(description = "연소득 × 80%(원 미만 버림). 연소득이 없으면 null", nullable = true)
        Money byIncomeRatio,

        @Schema(description = "연소득 ÷ 12 × 40% × maxTermMonths(원 미만 버림). 연소득·maxTermMonths 가 없으면 null",
                nullable = true)
        Money byRepayment,

        @Schema(description = "상품 최대 한도(5,000만 원)")
        Money cap,

        @Schema(description = "차감한 기존 대출 = loans 의 ACTIVE 잔액 합(당·타행 구분 없음)")
        Money existingLoans,

        @Schema(description = "DSR 적용 여부. 모의 계산은 DSR 을 반영하지 않아 항상 false", example = "false")
        boolean dsrApplied,

        @Schema(description = "계산 기준일(오늘, KST)", example = "2026-11-17")
        LocalDate asOfDate
) {

    public enum ReasonCode {
        /** 가입 조건 중 하나 이상 미충족. */
        NOT_ELIGIBLE,
        /** 체류 만료 전월까지 대출 가능 기간이 6개월 미만. */
        TERM_TOO_SHORT
    }

    public enum IncomeSource {
        INCOME_RECORD, CONTRACT_SALARY, NONE
    }
}
