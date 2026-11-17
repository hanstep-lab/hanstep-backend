package com.hanstep.credit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.common.dto.Money;
import com.hanstep.credit.dto.SolGlobalLoanLimitEstimate;
import com.hanstep.credit.dto.SolGlobalLoanLimitEstimate.IncomeSource;
import com.hanstep.credit.dto.SolGlobalLoanLimitEstimate.ReasonCode;
import com.hanstep.credit.service.SolGlobalLoanCalculator.AnnualIncome;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SolGlobalLoanCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    @Test
    void 만기_상한은_체류_만료월의_전월_말일이고_기간은_꽉_찬_달_수() {
        // 2026-11-17 ~ 2027-09-30 = 10개월 13일 → 10
        SolGlobalLoanLimitEstimate e = estimate(LocalDate.of(2027, 10, 4), 32_100_000, 0, true);

        assertThat(e.latestMaturityOn()).isEqualTo(LocalDate.of(2027, 9, 30));
        assertThat(e.maxTermMonths()).isEqualTo(10);
        // 만료일이 그 달 1일이어도 전월 말일
        assertThat(SolGlobalLoanCalculator.latestMaturityOn(LocalDate.of(2027, 10, 1)))
                .isEqualTo(LocalDate.of(2027, 9, 30));
        // 3월 만료면 2월 말일(평년 28일)
        assertThat(SolGlobalLoanCalculator.latestMaturityOn(LocalDate.of(2027, 3, 31)))
                .isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @Test
    void 대출_기간은_36개월이_상한() {
        SolGlobalLoanLimitEstimate e = estimate(LocalDate.of(2031, 1, 1), 30_000_000, 0, true);

        assertThat(e.maxTermMonths()).isEqualTo(36);
        // 30,000,000 ÷ 12 × 0.4 × 36 = 36,000,000
        assertThat(e.byRepayment()).isEqualTo(Money.krw(36_000_000));
    }

    @Test
    void 대출_기간이_6개월_미만이면_한도_null_TERM_TOO_SHORT() {
        // 만료 2027-05-10 → 상한 2027-04-30 → 5개월 13일 → 5
        SolGlobalLoanLimitEstimate e = estimate(LocalDate.of(2027, 5, 10), 32_100_000, 0, true);

        assertThat(e.maxTermMonths()).isEqualTo(5);
        assertThat(e.estimatedLimit()).isNull();
        assertThat(e.reasonCode()).isEqualTo(ReasonCode.TERM_TOO_SHORT);
        // 계산 항은 그대로 보여 준다
        assertThat(e.byIncomeRatio()).isEqualTo(Money.krw(25_680_000));
        assertThat(e.byRepayment()).isEqualTo(Money.krw(5_350_000));
    }

    @Test
    void 대출_기간이_딱_6개월이면_계산한다() {
        // 만료 2027-06-10 → 상한 2027-05-31 → 6개월 14일 → 6
        SolGlobalLoanLimitEstimate e = estimate(LocalDate.of(2027, 6, 10), 32_100_000, 0, true);

        assertThat(e.maxTermMonths()).isEqualTo(6);
        assertThat(e.reasonCode()).isNull();
        // 32,100,000 × 0.4 × 6 ÷ 12 = 6,420,000
        assertThat(e.estimatedLimit()).isEqualTo(Money.krw(6_420_000));
    }

    @Test
    void 상환_기준_항이_더_작으면_그것을_쓴다() {
        SolGlobalLoanLimitEstimate e = estimate(LocalDate.of(2027, 10, 4), 32_100_000, 0, true);

        assertThat(e.byIncomeRatio()).isEqualTo(Money.krw(25_680_000));
        assertThat(e.byRepayment()).isEqualTo(Money.krw(10_700_000));
        assertThat(e.estimatedLimit()).isEqualTo(Money.krw(10_700_000));
        assertThat(e.reasonCode()).isNull();
        assertThat(e.dsrApplied()).isFalse();
        assertThat(e.asOfDate()).isEqualTo(TODAY);
    }

    @Test
    void 연소득_비율_항이_더_작으면_그것을_쓴다() {
        // 20,000,000 × 0.8 = 16,000,000 < 20,000,000 × 0.4 × 36 ÷ 12 = 24,000,000
        SolGlobalLoanLimitEstimate e = estimate(LocalDate.of(2031, 1, 1), 20_000_000, 0, true);

        assertThat(e.estimatedLimit()).isEqualTo(Money.krw(16_000_000));
    }

    @Test
    void 두_항이_모두_크면_5000만원() {
        SolGlobalLoanLimitEstimate e = estimate(LocalDate.of(2031, 1, 1), 100_000_000, 0, true);

        assertThat(e.byIncomeRatio()).isEqualTo(Money.krw(80_000_000));
        assertThat(e.byRepayment()).isEqualTo(Money.krw(120_000_000));
        assertThat(e.cap()).isEqualTo(Money.krw(50_000_000));
        assertThat(e.estimatedLimit()).isEqualTo(Money.krw(50_000_000));
    }

    @Test
    void 기존_대출을_빼고_음수면_0() {
        assertThat(estimate(LocalDate.of(2027, 10, 4), 32_100_000, 3_000_000, true).estimatedLimit())
                .isEqualTo(Money.krw(7_700_000));
        SolGlobalLoanLimitEstimate over = estimate(LocalDate.of(2027, 10, 4), 32_100_000, 12_000_000, true);
        assertThat(over.estimatedLimit()).isEqualTo(Money.krw(0));
        assertThat(over.existingLoans()).isEqualTo(Money.krw(12_000_000));
        assertThat(over.reasonCode()).isNull();
    }

    @Test
    void 한도는_만원_단위_버림() {
        // 31,234,567 × 0.4 × 10 ÷ 12 = 10,411,522.33 → 항 10,411,522 → 한도 10,410,000
        SolGlobalLoanLimitEstimate e = estimate(LocalDate.of(2027, 10, 4), 31_234_567, 0, true);

        assertThat(e.byIncomeRatio()).isEqualTo(Money.krw(24_987_653));
        assertThat(e.byRepayment()).isEqualTo(Money.krw(10_411_522));
        assertThat(e.estimatedLimit()).isEqualTo(Money.krw(10_410_000));
        // 기존 대출 차감 뒤에 버린다: 10,411,522 − 1,234 = 10,410,288 → 10,410,000
        assertThat(estimate(LocalDate.of(2027, 10, 4), 31_234_567, 1_234, true).estimatedLimit())
                .isEqualTo(Money.krw(10_410_000));
    }

    @Test
    void 조건_미충족이면_기간이_짧아도_NOT_ELIGIBLE이_우선() {
        SolGlobalLoanLimitEstimate e = estimate(LocalDate.of(2027, 5, 10), 32_100_000, 0, false);

        assertThat(e.estimatedLimit()).isNull();
        assertThat(e.reasonCode()).isEqualTo(ReasonCode.NOT_ELIGIBLE);
    }

    @Test
    void 연소득이_없으면_항이_null이고_NOT_ELIGIBLE() {
        SolGlobalLoanLimitEstimate e = SolGlobalLoanCalculator.estimate(TODAY, LocalDate.of(2027, 10, 4),
                AnnualIncome.NONE, BigDecimal.ZERO, true);

        assertThat(e.annualIncome()).isNull();
        assertThat(e.incomeSource()).isEqualTo(IncomeSource.NONE);
        assertThat(e.byIncomeRatio()).isNull();
        assertThat(e.byRepayment()).isNull();
        assertThat(e.reasonCode()).isEqualTo(ReasonCode.NOT_ELIGIBLE);
    }

    @Test
    void 만료일이_없으면_기간이_null이고_TERM_TOO_SHORT() {
        SolGlobalLoanLimitEstimate e = estimate(null, 32_100_000, 0, true);

        assertThat(e.latestMaturityOn()).isNull();
        assertThat(e.maxTermMonths()).isNull();
        assertThat(e.byRepayment()).isNull();
        assertThat(e.reasonCode()).isEqualTo(ReasonCode.TERM_TOO_SHORT);
    }

    @Test
    void 연소득_소수는_원_미만_버림() {
        SolGlobalLoanLimitEstimate e = SolGlobalLoanCalculator.estimate(TODAY, LocalDate.of(2027, 10, 4),
                new AnnualIncome(new BigDecimal("32100000.00"), IncomeSource.INCOME_RECORD, 2025),
                new BigDecimal("0.00"), true);

        assertThat(e.annualIncome()).isEqualTo(Money.krw(32_100_000));
        assertThat(e.incomeYear()).isEqualTo(2025);
        assertThat(e.estimatedLimit()).isEqualTo(Money.krw(10_700_000));
    }

    private static SolGlobalLoanLimitEstimate estimate(LocalDate expiresOn, long income, long existingLoans,
                                                       boolean met) {
        return SolGlobalLoanCalculator.estimate(TODAY, expiresOn,
                new AnnualIncome(BigDecimal.valueOf(income), IncomeSource.INCOME_RECORD, 2025),
                BigDecimal.valueOf(existingLoans), met);
    }
}
