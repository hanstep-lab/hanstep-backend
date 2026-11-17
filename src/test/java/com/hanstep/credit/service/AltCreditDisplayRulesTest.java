package com.hanstep.credit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.credit.dto.AltCreditAssessmentResponse.EligibilityCheck;
import com.hanstep.credit.dto.AltCreditAssessmentResponse.Factor;
import com.hanstep.credit.dto.AltCreditAssessmentResponse.Factor.Level;
import com.hanstep.credit.dto.AltCreditAssessmentResponse.ScoreBand;
import com.hanstep.credit.dto.AltCreditInputs;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 표시용 필드 경계값(CR-001 v2). 구간은 이상~미만. 바꾸지 않는 값은 Reyes 입력을 쓴다. */
class AltCreditDisplayRulesTest {

    @ParameterizedTest
    @CsvSource({"0, 1, 1, 200", "1, 1, 1, 200", "200, 1, 1, 200", "201, 2, 201, 400", "400, 2, 201, 400",
            "401, 3, 401, 600", "600, 3, 401, 600", "601, 4, 601, 800", "800, 4, 601, 800", "801, 5, 801, 1000",
            "1000, 5, 801, 1000", "1001, 5, 801, 1000"})
    void 점수는_200점씩_5구간이고_범위_밖은_양끝_구간(int score, int index, int min, int max) {
        assertThat(AltCreditDisplayRules.scoreBand(score)).isEqualTo(new ScoreBand(index, min, max));
    }

    @ParameterizedTest
    @CsvSource({"0, GOOD", "1, FAIR", "2, FAIR", "3, NEEDS_IMPROVEMENT", "6, NEEDS_IMPROVEMENT"})
    void 통신비_공과금_연체_달_수(int lateMonths, Level level) {
        List<Factor> factors = AltCreditDisplayRules.factors(
                inputs(47, 10, true, 2_350_000, 12, lateMonths, lateMonths, "0.2979", 6));

        assertThat(factor(factors, Factor.Key.TEL_LATE)).isEqualTo(new Factor(Factor.Key.TEL_LATE, level, lateMonths));
        assertThat(factor(factors, Factor.Key.UTIL_LATE))
                .isEqualTo(new Factor(Factor.Key.UTIL_LATE, level, lateMonths));
    }

    @ParameterizedTest
    @CsvSource({"6, GOOD", "5, GOOD", "4, FAIR", "3, FAIR", "2, NEEDS_IMPROVEMENT", "1, NEEDS_IMPROVEMENT",
            ", NO_RECORD"})
    void 송금한_달_수_송금이_없으면_NO_RECORD(Integer remitMonths, Level level) {
        List<Factor> factors = AltCreditDisplayRules.factors(
                inputs(47, 10, true, 2_350_000, 12, 0, 0, "0.2979", remitMonths));

        assertThat(factor(factors, Factor.Key.REMIT_REGULARITY))
                .isEqualTo(new Factor(Factor.Key.REMIT_REGULARITY, level, remitMonths));
    }

    @ParameterizedTest
    @CsvSource({"0, GOOD", "0.3999, GOOD", "0.4000, FAIR", "0.5999, FAIR", "0.6000, NEEDS_IMPROVEMENT",
            "1, NEEDS_IMPROVEMENT"})
    void 송금_비율(String remitRatio, Level level) {
        List<Factor> factors = AltCreditDisplayRules.factors(
                inputs(47, 10, true, 2_350_000, 12, 0, 0, remitRatio, 6));

        assertThat(factor(factors, Factor.Key.REMIT_RATIO))
                .isEqualTo(new Factor(Factor.Key.REMIT_RATIO, level, new BigDecimal(remitRatio)));
    }

    @Test
    void 월소득이_0이면_송금_비율은_NO_RECORD() {
        // 소득이 없으면 inputs 의 remitRatio 는 0(송금은 있을 수 있음)
        List<Factor> factors = AltCreditDisplayRules.factors(inputs(47, 10, true, 0, 0, 0, 0, "0", 2));

        assertThat(factor(factors, Factor.Key.REMIT_RATIO))
                .isEqualTo(new Factor(Factor.Key.REMIT_RATIO, Level.NO_RECORD, BigDecimal.ZERO));
        assertThat(factor(factors, Factor.Key.REMIT_REGULARITY).level()).isEqualTo(Level.NEEDS_IMPROVEMENT);
    }

    @ParameterizedTest
    @CsvSource({"6, NEEDS_IMPROVEMENT", "23, NEEDS_IMPROVEMENT", "24, FAIR", "35, FAIR", "36, GOOD", "120, GOOD"})
    void 누적_체류_개월(int stayMonths, Level level) {
        List<Factor> factors = AltCreditDisplayRules.factors(
                inputs(stayMonths, 10, true, 2_350_000, 12, 0, 0, "0.2979", 6));

        assertThat(factor(factors, Factor.Key.STAY_DURATION))
                .isEqualTo(new Factor(Factor.Key.STAY_DURATION, level, stayMonths));
    }

    @Test
    void 요인은_정해진_순서로_5개이고_value는_inputs_원값() {
        List<Factor> factors = AltCreditDisplayRules.factors(inputs(30, 10, true, 1_800_000, 4, 2, 4, "0.45", 3));

        assertThat(factors).containsExactly(
                new Factor(Factor.Key.TEL_LATE, Level.FAIR, 2),
                new Factor(Factor.Key.UTIL_LATE, Level.NEEDS_IMPROVEMENT, 4),
                new Factor(Factor.Key.REMIT_REGULARITY, Level.FAIR, 3),
                new Factor(Factor.Key.REMIT_RATIO, Level.FAIR, new BigDecimal("0.45")),
                new Factor(Factor.Key.STAY_DURATION, Level.FAIR, 30));
    }

    @ParameterizedTest
    @CsvSource({"0, false", "2, false", "3, true", "12, true"})
    void 급여_입금_3개월_이상(int salaryMonths, boolean met) {
        List<EligibilityCheck> checks = AltCreditDisplayRules.eligibilityChecks(
                inputs(47, 10, true, 2_350_000, salaryMonths, 0, 0, "0.2979", 6));

        assertThat(checks.get(1)).isEqualTo(new EligibilityCheck(EligibilityCheck.Key.SALARY_3M, met, salaryMonths));
    }

    @ParameterizedTest
    @CsvSource({"0, false", "6, false", "7, true", "20, true"})
    void 체류_만료까지_6개월_초과(int monthsToExpiry, boolean met) {
        List<EligibilityCheck> checks = AltCreditDisplayRules.eligibilityChecks(
                inputs(47, monthsToExpiry, true, 2_350_000, 12, 0, 0, "0.2979", 6));

        assertThat(checks.get(2))
                .isEqualTo(new EligibilityCheck(EligibilityCheck.Key.EXPIRY_OVER_6M, met, monthsToExpiry));
    }

    @Test
    void 만료일이_없으면_만료_점검의_met과_value는_null() {
        List<EligibilityCheck> checks = AltCreditDisplayRules.eligibilityChecks(
                inputs(47, null, true, 2_350_000, 12, 0, 0, "0.2979", 6));

        assertThat(checks.get(2)).isEqualTo(new EligibilityCheck(EligibilityCheck.Key.EXPIRY_OVER_6M, null, null));
    }

    @Test
    void 근로_불가_자격이면_근로_점검은_미충족() {
        List<EligibilityCheck> checks = AltCreditDisplayRules.eligibilityChecks(
                inputs(47, 10, false, 2_350_000, 12, 0, 0, "0.2979", 6));

        assertThat(checks).extracting(EligibilityCheck::key).containsExactly(EligibilityCheck.Key.WORK_PERMITTED,
                EligibilityCheck.Key.SALARY_3M, EligibilityCheck.Key.EXPIRY_OVER_6M);
        assertThat(checks.get(0)).isEqualTo(new EligibilityCheck(EligibilityCheck.Key.WORK_PERMITTED, false, false));
    }

    @Test
    void Reyes_814점은_5구간_요인_모두_GOOD_점검_모두_충족() {
        // 누적 체류 47개월(2022-12-05~2026-11), 만료까지 10개월, 월소득 235만, 급여 12개월, 연체 0, 송금 6개월·0.2979
        AltCreditInputs reyes = inputs(47, 10, true, 2_350_000, 12, 0, 0, "0.2979", 6);

        assertThat(AltCreditDisplayRules.scoreBand(814)).isEqualTo(new ScoreBand(5, 801, 1000));
        assertThat(AltCreditDisplayRules.factors(reyes)).containsExactly(
                new Factor(Factor.Key.TEL_LATE, Level.GOOD, 0),
                new Factor(Factor.Key.UTIL_LATE, Level.GOOD, 0),
                new Factor(Factor.Key.REMIT_REGULARITY, Level.GOOD, 6),
                new Factor(Factor.Key.REMIT_RATIO, Level.GOOD, new BigDecimal("0.2979")),
                new Factor(Factor.Key.STAY_DURATION, Level.GOOD, 47));
        assertThat(AltCreditDisplayRules.eligibilityChecks(reyes)).containsExactly(
                new EligibilityCheck(EligibilityCheck.Key.WORK_PERMITTED, true, true),
                new EligibilityCheck(EligibilityCheck.Key.SALARY_3M, true, 12),
                new EligibilityCheck(EligibilityCheck.Key.EXPIRY_OVER_6M, true, 10));
    }

    private static Factor factor(List<Factor> factors, Factor.Key key) {
        return factors.stream().filter(factor -> factor.key() == key).findFirst().orElseThrow();
    }

    private static AltCreditInputs inputs(int stayMonths, Integer monthsToExpiry, boolean workPermitted,
                                          long monthlyIncome, int salaryMonths, int telLate, int utilLate,
                                          String remitRatio, Integer remitMonths) {
        return new AltCreditInputs(stayMonths, monthsToExpiry, workPermitted, monthlyIncome, salaryMonths, true,
                false, false, telLate, utilLate, new BigDecimal(remitRatio), remitMonths, LocalDate.of(2026, 11, 17),
                LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 5, 1));
    }
}
