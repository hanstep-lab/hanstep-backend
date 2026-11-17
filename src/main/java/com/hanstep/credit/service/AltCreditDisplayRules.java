package com.hanstep.credit.service;

import com.hanstep.credit.dto.AltCreditAssessmentResponse.EligibilityCheck;
import com.hanstep.credit.dto.AltCreditAssessmentResponse.Factor;
import com.hanstep.credit.dto.AltCreditAssessmentResponse.Factor.Level;
import com.hanstep.credit.dto.AltCreditAssessmentResponse.ScoreBand;
import com.hanstep.credit.dto.AltCreditInputs;
import java.math.BigDecimal;
import java.util.List;

/**
 * 대안신용 모의 사전진단 응답의 표시용 필드(scoreBand·factors·eligibilityChecks) 경계값.
 * 표시용, 금리·한도 계산과 무관, CR-001 v2. 구간은 모두 이상~미만이고, 판정은 AI 의 decision·reasonCode 를 따른다.
 */
public final class AltCreditDisplayRules {

    // 점수 구간: 200점씩 5구간(1~200, …, 801~1,000)
    private static final int SCORE_BAND_WIDTH = 200;
    private static final int SCORE_BAND_COUNT = 5;

    // TEL_LATE·UTIL_LATE(연체가 있었던 달 수): 0 GOOD, 1~2 FAIR, 3 이상 NEEDS_IMPROVEMENT
    private static final int LATE_FAIR_FROM = 1;
    private static final int LATE_NEEDS_IMPROVEMENT_FROM = 3;

    // REMIT_REGULARITY(최근 6개월 중 송금한 달 수): 5~6 GOOD, 3~4 FAIR, 1~2 NEEDS_IMPROVEMENT, 송금 없음(null) NO_RECORD
    private static final int REMIT_MONTHS_GOOD_FROM = 5;
    private static final int REMIT_MONTHS_FAIR_FROM = 3;

    // REMIT_RATIO(소득 대비 송금 비율): 0.4 미만 GOOD, 0.4~0.6 FAIR, 0.6 이상 NEEDS_IMPROVEMENT, 월소득 0 이면 NO_RECORD
    private static final BigDecimal REMIT_RATIO_FAIR_FROM = new BigDecimal("0.4");
    private static final BigDecimal REMIT_RATIO_NEEDS_IMPROVEMENT_FROM = new BigDecimal("0.6");

    // STAY_DURATION(누적 체류 개월): 36 이상 GOOD, 24~35 FAIR, 24 미만 NEEDS_IMPROVEMENT
    private static final int STAY_GOOD_FROM = 36;
    private static final int STAY_FAIR_FROM = 24;

    // SALARY_3M: 급여 입금 3개월 이상. EXPIRY_OVER_6M: 만료까지 6개월 초과
    private static final int SALARY_MONTHS_MIN = 3;
    private static final int EXPIRY_MONTHS_OVER = 6;

    private AltCreditDisplayRules() {
    }

    /** index = min(5, max(1, ceil(score / 200))). */
    public static ScoreBand scoreBand(int score) {
        int index = Math.min(SCORE_BAND_COUNT, Math.max(1, Math.ceilDiv(score, SCORE_BAND_WIDTH)));
        return new ScoreBand(index, (index - 1) * SCORE_BAND_WIDTH + 1, index * SCORE_BAND_WIDTH);
    }

    /** value 는 inputs 의 원래 값. */
    public static List<Factor> factors(AltCreditInputs inputs) {
        return List.of(
                new Factor(Factor.Key.TEL_LATE, lateLevel(inputs.telLate6m()), inputs.telLate6m()),
                new Factor(Factor.Key.UTIL_LATE, lateLevel(inputs.utilLate6m()), inputs.utilLate6m()),
                new Factor(Factor.Key.REMIT_REGULARITY, remitRegularityLevel(inputs.remitMonths6m()),
                        inputs.remitMonths6m()),
                new Factor(Factor.Key.REMIT_RATIO, remitRatioLevel(inputs.monthlyIncome(), inputs.remitRatio()),
                        inputs.remitRatio()),
                new Factor(Factor.Key.STAY_DURATION, stayLevel(inputs.stayDurationMonths()),
                        inputs.stayDurationMonths()));
    }

    /** 만료일이 없으면(monthsToExpiry null) EXPIRY_OVER_6M 의 met 도 null. */
    public static List<EligibilityCheck> eligibilityChecks(AltCreditInputs inputs) {
        Integer monthsToExpiry = inputs.monthsToExpiry();
        return List.of(
                new EligibilityCheck(EligibilityCheck.Key.WORK_PERMITTED, inputs.workPermitted(),
                        inputs.workPermitted()),
                new EligibilityCheck(EligibilityCheck.Key.SALARY_3M, inputs.salaryMonths() >= SALARY_MONTHS_MIN,
                        inputs.salaryMonths()),
                new EligibilityCheck(EligibilityCheck.Key.EXPIRY_OVER_6M,
                        monthsToExpiry == null ? null : monthsToExpiry > EXPIRY_MONTHS_OVER, monthsToExpiry));
    }

    private static Level lateLevel(int lateMonths) {
        if (lateMonths < LATE_FAIR_FROM) {
            return Level.GOOD;
        }
        return lateMonths < LATE_NEEDS_IMPROVEMENT_FROM ? Level.FAIR : Level.NEEDS_IMPROVEMENT;
    }

    // inputs 는 송금하지 않았으면 0 이 아니라 null 을 준다
    private static Level remitRegularityLevel(Integer remitMonths) {
        if (remitMonths == null) {
            return Level.NO_RECORD;
        }
        if (remitMonths >= REMIT_MONTHS_GOOD_FROM) {
            return Level.GOOD;
        }
        return remitMonths >= REMIT_MONTHS_FAIR_FROM ? Level.FAIR : Level.NEEDS_IMPROVEMENT;
    }

    // 소득이 없으면 inputs 의 remitRatio 가 0 이라 GOOD 로 보이지 않게 NO_RECORD
    private static Level remitRatioLevel(long monthlyIncome, BigDecimal remitRatio) {
        if (monthlyIncome == 0) {
            return Level.NO_RECORD;
        }
        if (remitRatio.compareTo(REMIT_RATIO_FAIR_FROM) < 0) {
            return Level.GOOD;
        }
        return remitRatio.compareTo(REMIT_RATIO_NEEDS_IMPROVEMENT_FROM) < 0 ? Level.FAIR : Level.NEEDS_IMPROVEMENT;
    }

    private static Level stayLevel(int stayMonths) {
        if (stayMonths >= STAY_GOOD_FROM) {
            return Level.GOOD;
        }
        return stayMonths >= STAY_FAIR_FROM ? Level.FAIR : Level.NEEDS_IMPROVEMENT;
    }
}
