package com.hanstep.onboarding.dto;

import com.hanstep.commitment.dto.CommitmentListResponse;
import com.hanstep.finance.dto.MonthlyBalanceResponse;
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

        @Schema(description = "월 수지 요약(INT-06 balance·PRD-02 여유자금과 같은 값). 월소득은 최신 금융 분석(FIN-05) 값, "
                + "고정생활비·송금·변동지출은 commitments 에 그 묶음 항목이 있으면 입력 합계(commitments.totals 와 같은 값), "
                + "없으면 FIN-05 값. 분석 전이거나 쓸 금액이 비면 null", nullable = true)
        MonthlyBalanceResponse balance
) {

    @Schema(name = "SummaryUser", description = "사용자")
    public record User(
            @Schema(example = "1")
            Long id,

            @Schema(description = "표시 이름(영문)", example = "MARK ANTHONY REYES")
            String displayName,

            @Schema(description = "화면·답변 언어", example = "en")
            Language preferredLanguage,

            @Schema(description = "국적(ISO 3166-1 alpha-3, visa_profiles)", example = "PHL", nullable = true)
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
            long daysRemaining,

            @Schema(description = "만료까지 남은 개월 수(꽉 찬 달, 계산값). monthlyRequired 와 같은 규칙. 지났으면 null",
                    example = "10", nullable = true)
            Integer monthsRemaining
    ) {
    }
}
