package com.hanstep.credit.service;

import static com.hanstep.credit.domain.SolGlobalLoanProduct.INCOME_RATIO;
import static com.hanstep.credit.domain.SolGlobalLoanProduct.MAX_LIMIT;
import static com.hanstep.credit.domain.SolGlobalLoanProduct.MAX_TERM_MONTHS;
import static com.hanstep.credit.domain.SolGlobalLoanProduct.MIN_TERM_MONTHS;
import static com.hanstep.credit.domain.SolGlobalLoanProduct.REPAYMENT_RATIO;

import com.hanstep.common.dto.Money;
import com.hanstep.credit.dto.SolGlobalLoanLimitEstimate;
import com.hanstep.credit.dto.SolGlobalLoanLimitEstimate.IncomeSource;
import com.hanstep.credit.dto.SolGlobalLoanLimitEstimate.ReasonCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * SOL 글로벌론 모의 한도 계산(DB 를 읽지 않는 순수 계산). 금액은 BigDecimal 로 계산하고 원 단위 정수로 내보낸다.
 * <ul>
 *   <li>만기일 상한 = 체류 만료월의 전월 말일, maxTermMonths = 기준일~상한의 꽉 찬 달 수(36 상한)</li>
 *   <li>byIncomeRatio = 연소득 × 0.8, byRepayment = 연소득 × 0.4 × maxTermMonths ÷ 12 (각각 원 미만 버림)</li>
 *   <li>estimatedLimit = max(0, min(5,000만, byIncomeRatio, byRepayment) − 기존 대출), 만 원 단위 버림(임시)</li>
 *   <li>조건 미충족이면 NOT_ELIGIBLE, maxTermMonths 가 6 미만(또는 만료일 없음)이면 TERM_TOO_SHORT 로 한도 null</li>
 * </ul>
 */
public final class SolGlobalLoanCalculator {

    // 만 원 단위 버림(임시 기준)
    static final BigDecimal LIMIT_UNIT = BigDecimal.valueOf(10_000L);
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12L);

    private SolGlobalLoanCalculator() {
    }

    /** 연소득과 출처. 소득 정보가 없으면 amount·year 가 null 이고 source 는 NONE. */
    public record AnnualIncome(BigDecimal amount, IncomeSource source, Integer year) {

        public static final AnnualIncome NONE = new AnnualIncome(null, IncomeSource.NONE, null);
    }

    public static SolGlobalLoanLimitEstimate estimate(LocalDate today, LocalDate stayExpiresOn, AnnualIncome income,
                                                      BigDecimal existingLoans, boolean allConditionsMet) {
        LocalDate latestMaturityOn = latestMaturityOn(stayExpiresOn);
        Integer maxTermMonths = latestMaturityOn == null ? null
                : Math.min(MAX_TERM_MONTHS, AltCreditInputReader.fullMonths(today, latestMaturityOn));

        BigDecimal annual = income.amount();
        BigDecimal byIncomeRatio = annual == null ? null : won(annual.multiply(INCOME_RATIO));
        BigDecimal byRepayment = annual == null || maxTermMonths == null ? null
                : annual.multiply(REPAYMENT_RATIO).multiply(BigDecimal.valueOf(maxTermMonths))
                        .divide(MONTHS_PER_YEAR, 0, RoundingMode.DOWN);
        BigDecimal loans = won(existingLoans == null ? BigDecimal.ZERO : existingLoans);

        ReasonCode reason = null;
        BigDecimal limit = null;
        if (!allConditionsMet || annual == null) {
            reason = ReasonCode.NOT_ELIGIBLE;
        } else if (maxTermMonths == null || maxTermMonths < MIN_TERM_MONTHS) {
            reason = ReasonCode.TERM_TOO_SHORT;
        } else {
            BigDecimal base = MAX_LIMIT.min(byIncomeRatio).min(byRepayment);
            BigDecimal remaining = base.subtract(loans).max(BigDecimal.ZERO);
            limit = remaining.divide(LIMIT_UNIT, 0, RoundingMode.DOWN).multiply(LIMIT_UNIT);
        }

        return new SolGlobalLoanLimitEstimate(money(limit), reason, money(annual == null ? null : won(annual)),
                income.source(), income.year(), latestMaturityOn, maxTermMonths, money(byIncomeRatio),
                money(byRepayment), money(MAX_LIMIT), money(loans), false, today);
    }

    /** 만기일 상한 = 체류 만료월의 전월 말일. 만료일이 없으면 null. */
    static LocalDate latestMaturityOn(LocalDate stayExpiresOn) {
        return stayExpiresOn == null ? null : YearMonth.from(stayExpiresOn).minusMonths(1).atEndOfMonth();
    }

    private static BigDecimal won(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.DOWN);
    }

    private static Money money(BigDecimal amount) {
        return amount == null ? null : Money.krw(amount.longValueExact());
    }
}
