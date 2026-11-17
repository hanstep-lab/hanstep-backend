package com.hanstep.credit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hanstep.credit.domain.AltCreditDecision;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * AI 응답(AltCreditAssessment) 필드 그대로 + inputs·mockFields + 표시용 필드(scoreBand·factors·eligibilityChecks).
 * 표시용 필드는 점수·inputs 로 Spring 이 나눈 것(AltCreditDisplayRules)이라 계산에 쓰이지 않는다. 저장하지 않는다.
 */
@Schema(description = "대안신용 모의 사전진단 결과. 모의 사전진단이며 실제 대출 승인이나 신용점수가 아닙니다. "
        + "AI 계산 응답 필드에 inputs(보낸 입력), mockFields(Mock·임시값 입력 이름), 표시용 필드(scoreBand·factors·"
        + "eligibilityChecks)를 붙인 것이고 저장하지 않는다. 표시용 필드는 금리·한도 계산과 무관하다")
public record AltCreditAssessmentResponse(
        @Schema(description = "모델 버전", example = "alt-credit-v9")
        String modelVersion,

        @Schema(description = "입력이 Mock 데이터 기반이면 true. 지금은 항상 true", example = "true")
        boolean isMock,

        @Schema(description = "모형이 추정한 모의 부도확률(합성 데이터로 학습, 실제 연체 확률이 아님)",
                example = "0.010967608614541395")
        double pd,

        @Schema(description = "모의 점수(1~1,000). 실제 신용점수가 아님", example = "814")
        int score,

        @Schema(description = "연 금리 구성(소수, 0.0383 = 3.83%). 소득이 없으면 null", nullable = true)
        RateBreakdown rateBreakdown,

        @Schema(description = "기준금리·공시 가산금리 기준일", example = "2026-11-11")
        LocalDate rateBaseDate,

        @Schema(description = "모의 대출기간(개월). ELIGIBLE 이 아니면 0", example = "9")
        int loanTermMonths,

        @Schema(description = "모의 최소한도(원). ELIGIBLE 이 아니면 0", example = "6200000")
        long minLimit,

        @Schema(description = "모의 최대한도(원). ELIGIBLE 이 아니면 0", example = "7800000")
        long maxLimit,

        @Schema(description = "최대한도를 정한 상한. PRODUCT_MAX(상품 최대 3천만 원)·ANNUAL_INCOME_80(연소득×80%)·"
                + "MONTHLY_PAYMENT_40_TERM(월소득×40%×개월 수)·SIMPLE_DSR_40(간이 DSR 40%). ELIGIBLE 이 아니면 null",
                nullable = true, allowableValues = {"PRODUCT_MAX", "ANNUAL_INCOME_80", "MONTHLY_PAYMENT_40_TERM",
                "SIMPLE_DSR_40"}, example = "SIMPLE_DSR_40")
        String maxLimitBinding,

        @Schema(description = "모의 판정(실제 승인 여부가 아님). "
                + "ELIGIBLE: 모의 기준상 대상이고 조건도 충족(대출기간·한도를 계산함). "
                + "NOT_ELIGIBLE: 대상 아님(reasonCode NO_INCOME 소득 없음, WORK_NOT_PERMITTED 근로 가능 자격 미확인, "
                + "SALARY_UNDER_3M 급여 입금 3개월 미만). "
                + "CONDITION_NOT_MET: 대상이지만 조건 미충족(reasonCode EXPIRY_WITHIN_6M 체류 만료 6개월 이내, "
                + "RATE_OVER_LEGAL_MAX 산출 금리가 법정 최고금리 초과)", example = "ELIGIBLE")
        AltCreditDecision decision,

        @Schema(description = "판정 사유. ELIGIBLE 이면 null", nullable = true,
                allowableValues = {"NO_INCOME", "WORK_NOT_PERMITTED", "SALARY_UNDER_3M", "EXPIRY_WITHIN_6M",
                        "RATE_OVER_LEGAL_MAX"})
        String reasonCode,

        @ArraySchema(arraySchema = @Schema(description = "입력을 바꿔 계산한 경우의 코드. REMIT_RATIO_CLIPPED: 송금 비율이 "
                + "학습 범위(0.8)를 넘어 0.8 로 계산. 없으면 빈 목록"))
        List<String> inputWarnings,

        @Schema(description = "고지 문구(AI 가 준 값)", example = "모의 사전진단이며 실제 대출 승인이나 신용점수가 아닙니다")
        String disclaimer,

        AltCreditInputs inputs,

        // @ArraySchema 의 arraySchema example 은 문서에 나오지 않아 @Schema 에 둔다(ConnectionResponse.scopes 와 같음)
        @Schema(description = "inputs 중 Mock 데이터·임시값에서 나온 입력 이름(Mock 거래·계좌, 시드 청구 납부 내역)",
                example = "[\"monthlyIncome\", \"salaryMonths\", \"shinhanPayroll\", \"hasSubscription\", "
                        + "\"telLate6m\", \"utilLate6m\", \"remitRatio\", \"remitMonths6m\"]")
        List<String> mockFields,

        @Schema(description = "표시용 점수 구간(200점씩 5구간). 금리·한도 계산과 무관")
        ScoreBand scoreBand,

        @Schema(description = "표시용 요인 등급 5개(항상 이 순서: TEL_LATE, UTIL_LATE, REMIT_REGULARITY, REMIT_RATIO, "
                + "STAY_DURATION). 금리·한도 계산과 무관하고 문구는 내려주지 않는다(FE 언어별 문구표)",
                example = "[{\"key\": \"TEL_LATE\", \"level\": \"GOOD\", \"value\": 0}, "
                        + "{\"key\": \"UTIL_LATE\", \"level\": \"GOOD\", \"value\": 0}, "
                        + "{\"key\": \"REMIT_REGULARITY\", \"level\": \"GOOD\", \"value\": 6}, "
                        + "{\"key\": \"REMIT_RATIO\", \"level\": \"GOOD\", \"value\": 0.2979}, "
                        + "{\"key\": \"STAY_DURATION\", \"level\": \"GOOD\", \"value\": 47}]")
        List<Factor> factors,

        @Schema(description = "표시용 대상·조건 점검 3개(항상 이 순서: WORK_PERMITTED, SALARY_3M, EXPIRY_OVER_6M). "
                + "표시용이며 최종 판정은 decision·reasonCode 가 기준이다(점검에 없는 NO_INCOME·RATE_OVER_LEGAL_MAX 가 "
                + "있어 셋 다 충족이어도 ELIGIBLE 이 아닐 수 있음)",
                example = "[{\"key\": \"WORK_PERMITTED\", \"met\": true, \"value\": true}, "
                        + "{\"key\": \"SALARY_3M\", \"met\": true, \"value\": 12}, "
                        + "{\"key\": \"EXPIRY_OVER_6M\", \"met\": true, \"value\": 10}]")
        List<EligibilityCheck> eligibilityChecks
) {

    /** 연 금리(소수). final = base + spread − preferential, spread = creditPremium + capitalCost + otherSpread. */
    @Schema(name = "AltCreditRateBreakdown", description = "모의 연 금리 구성(소수)")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RateBreakdown(
            @Schema(description = "기준금리(금융채 6개월)", example = "0.0383") double base,
            @Schema(description = "신용프리미엄(모의 부도확률 × LGD)", example = "0.004935423876543628")
            double creditPremium,
            @Schema(description = "자본비용(바젤 필요자본 × 자기자본비용)", example = "0.0038057419439715546")
            double capitalCost,
            @Schema(description = "기타 가산(고정)", example = "0.073547") double otherSpread,
            @Schema(description = "가산금리", example = "0.08228816582051518") double spread,
            @Schema(description = "우대금리(신한 급여이체·청약·대환)", example = "0.01") double preferential,
            // AI 필드 이름은 final(자바 예약어라 이름만 바꿔 받는다)
            @JsonProperty("final") @Schema(description = "최종 금리", example = "0.11058816582051519") double finalRate
    ) {
    }

    /** index = min(5, max(1, ceil(score / 200))), min = (index − 1) × 200 + 1, max = index × 200. */
    @Schema(name = "AltCreditScoreBand", description = "표시용 점수 구간. 1(1~200)·2(201~400)·3(401~600)·4(601~800)·"
            + "5(801~1,000)")
    public record ScoreBand(
            @Schema(description = "구간 번호(1~5)", example = "5") int index,
            @Schema(description = "구간 최저 점수", example = "801") int min,
            @Schema(description = "구간 최고 점수", example = "1000") int max
    ) {
    }

    /** 요인 등급. 구간은 AltCreditDisplayRules. */
    @Schema(name = "AltCreditFactor", description = "표시용 요인 등급. 구간은 모두 이상~미만")
    public record Factor(
            @Schema(description = "요인. TEL_LATE(inputs.telLate6m)·UTIL_LATE(utilLate6m)·REMIT_REGULARITY"
                    + "(remitMonths6m)·REMIT_RATIO(remitRatio)·STAY_DURATION(stayDurationMonths)", example = "TEL_LATE")
            Key key,

            @Schema(description = "등급. TEL_LATE·UTIL_LATE: 0 GOOD, 1~2 FAIR, 3 이상 NEEDS_IMPROVEMENT. "
                    + "REMIT_REGULARITY: 5~6 GOOD, 3~4 FAIR, 1~2 NEEDS_IMPROVEMENT, 송금 없음(null) NO_RECORD. "
                    + "REMIT_RATIO: 0.4 미만 GOOD, 0.4~0.6 FAIR, 0.6 이상 NEEDS_IMPROVEMENT, 월소득 0 이면 NO_RECORD. "
                    + "STAY_DURATION: 36 이상 GOOD, 24~35 FAIR, 24 미만 NEEDS_IMPROVEMENT", example = "GOOD")
            Level level,

            @Schema(description = "key 에 해당하는 inputs 의 원래 값. REMIT_REGULARITY 는 송금이 없으면 null",
                    nullable = true, example = "0")
            Number value
    ) {

        public enum Key {
            TEL_LATE, UTIL_LATE, REMIT_REGULARITY, REMIT_RATIO, STAY_DURATION
        }

        public enum Level {
            GOOD, FAIR, NEEDS_IMPROVEMENT, NO_RECORD
        }
    }

    /** 대상·조건 점검. 표시용이며 판정은 decision·reasonCode 를 따른다. */
    @Schema(name = "AltCreditEligibilityCheck", description = "표시용 대상·조건 점검. 최종 판정은 decision·reasonCode 기준")
    public record EligibilityCheck(
            @Schema(description = "점검 항목. WORK_PERMITTED(근로 가능 자격)·SALARY_3M(급여 입금 3개월 이상)·"
                    + "EXPIRY_OVER_6M(체류 만료까지 6개월 초과)", example = "WORK_PERMITTED")
            Key key,

            @Schema(description = "충족 여부. EXPIRY_OVER_6M 은 만료일이 없으면(monthsToExpiry null) null",
                    nullable = true, example = "true")
            Boolean met,

            @Schema(description = "판단에 쓴 inputs 값. WORK_PERMITTED 는 workPermitted(true/false), SALARY_3M 은 "
                    + "salaryMonths, EXPIRY_OVER_6M 은 monthsToExpiry(없으면 null)",
                    types = {"boolean", "integer", "null"}, example = "true")
            Object value
    ) {

        public enum Key {
            WORK_PERMITTED, SALARY_3M, EXPIRY_OVER_6M
        }
    }
}
