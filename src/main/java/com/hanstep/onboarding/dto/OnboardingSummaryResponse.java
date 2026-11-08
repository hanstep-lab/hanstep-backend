package com.hanstep.onboarding.dto;

import com.hanstep.commitment.dto.CommitmentListResponse;
import com.hanstep.common.dto.Money;
import com.hanstep.goal.dto.GoalResponse;
import com.hanstep.user.domain.Language;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "ON-006 온보딩 최종 확인 화면 전체. 잔여일·합계·여유자금은 계산값")
public record OnboardingSummaryResponse(
        User user,
        Visa visa,

        @ArraySchema(arraySchema = @Schema(description = "ACTIVE 목표"))
        List<GoalResponse> goals,

        CommitmentListResponse commitments,
        Balance balance
) {

    @Schema(name = "SummaryUser", description = "사용자")
    public record User(
            @Schema(example = "1")
            Long id,

            @Schema(description = "표시 이름(영문)", example = "NGUYEN VAN MINH")
            String displayName,

            @Schema(description = "화면·답변 언어", example = "en")
            Language preferredLanguage,

            @Schema(description = "국적(ISO 3166-1 alpha-3, visa_profiles)", example = "VNM", nullable = true)
            String nationalityCode
    ) {
    }

    @Schema(name = "SummaryVisa", description = "체류 정보(OCR 확정값, visa_profiles). 등록증을 아직 확정하지 않았으면 visa 전체가 null")
    public record Visa(
            @Schema(description = "체류자격 코드", example = "E-9")
            String visaTypeCode,

            @Schema(description = "체류자격 이름(영문)", example = "Non-professional Employment")
            String visaTypeName,

            @Schema(description = "체류 만료일(YYYY-MM-DD)", example = "2027-10-04")
            LocalDate stayExpiresOn,

            @Schema(description = "만료까지 남은 일수(오늘 KST 기준 계산값, 지났으면 음수)", example = "331")
            long daysRemaining
    ) {
    }

    /**
     * 여유자금 = 월소득 − 고정생활비 − 송금 − 변동지출. MANDATORY_SAVING 은 빼지 않는다.
     */
    @Schema(name = "SummaryBalance", description = "월 수지 요약(계산값). spare = avgMonthlyIncome − fixedLiving − remittance − variableExpense")
    public record Balance(
            @Schema(description = "월평균 소득(Mock 거래 기준, 없으면 자기신고 월소득)",
                    example = "{\"amount\": 2350000, \"currency\": \"KRW\"}")
            Money avgMonthlyIncome,

            @Schema(description = "고정생활비(RENT·UTILITY·TELECOM·LIVING_MINIMUM·OTHER 합)",
                    example = "{\"amount\": 520000, \"currency\": \"KRW\"}")
            Money fixedLiving,

            @Schema(description = "월 송금(REMITTANCE 합)", example = "{\"amount\": 700000, \"currency\": \"KRW\"}")
            Money remittance,

            @Schema(description = "변동지출 평균(거래 이력 있으면 거래 기준, 없으면 VARIABLE_EXPENSE 입력값)",
                    example = "{\"amount\": 450000, \"currency\": \"KRW\"}")
            Money variableExpense,

            @Schema(description = "variableExpense 근거. TRANSACTIONS=Mock 거래 평균, USER_INPUT=직접 입력",
                    example = "TRANSACTIONS", allowableValues = {"TRANSACTIONS", "USER_INPUT"})
            String variableExpenseSource,

            @Schema(description = "여유자금(음수 가능)", example = "{\"amount\": 680000, \"currency\": \"KRW\"}")
            Money spare,

            @Schema(description = "Mock 데이터 기반이면 true", example = "true")
            boolean isMock
    ) {
    }
}
