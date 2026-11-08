package com.hanstep.onboarding.dto;

import com.hanstep.common.dto.Money;
import com.hanstep.onboarding.domain.OnboardingProfile;
import com.hanstep.onboarding.domain.ResidencePurpose;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "온보딩 설문 응답(onboarding_profiles). 아직 저장 안 한 칸은 null")
public record OnboardingProfileResponse(
        @Schema(description = "ON-001 체류 목적", example = "WORK", nullable = true)
        ResidencePurpose residencePurpose,

        @Schema(description = "MVP에서는 받지 않음(null). 마이데이터 미연결 시 직접 입력은 2차 이후 검토",
                example = "{\"amount\": 2350000, \"currency\": \"KRW\"}")
        Money declaredMonthlyIncome
) {

    /** 아직 저장한 적 없는 사용자. */
    public static OnboardingProfileResponse empty() {
        return new OnboardingProfileResponse(null, null);
    }

    public static OnboardingProfileResponse from(OnboardingProfile profile) {
        Money income = profile.getDeclaredMonthlyIncome() == null ? null
                : Money.krw(profile.getDeclaredMonthlyIncome().longValue());
        return new OnboardingProfileResponse(profile.getResidencePurpose(), income);
    }
}
