package com.hanstep.user.dto;

import com.hanstep.onboarding.domain.OnboardingState;
import com.hanstep.onboarding.domain.OnboardingStep;
import com.hanstep.user.domain.Language;
import com.hanstep.user.domain.User;
import com.hanstep.user.domain.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "내 정보 + 온보딩 진행 상태")
public record UserMeResponse(
        @Schema(example = "1")
        Long id,

        @Schema(description = "표시 이름(영문)", example = "NGUYEN VAN MINH")
        String displayName,

        @Schema(description = "화면·답변 언어", example = "en", allowableValues = {"en", "ko", "vi", "zh"})
        Language preferredLanguage,

        @Schema(example = "ACTIVE")
        UserStatus status,

        Onboarding onboarding
) {

    @Schema(description = "온보딩 상태. FE는 completed=false 이면 currentStep 화면으로 보낸다")
    public record Onboarding(
            @Schema(description = "6단계 모두 완료 또는 건너뜀", example = "false")
            boolean completed,

            @Schema(description = "아직 끝나지 않은 첫 단계. completed=true 면 null", example = "LANGUAGE",
                    nullable = true)
            OnboardingStep currentStep
    ) {
    }

    public static UserMeResponse of(User user, OnboardingState state) {
        return new UserMeResponse(
                user.getId(),
                user.getDisplayName(),
                user.getPreferredLanguage(),
                user.getStatus(),
                new Onboarding(state.completed(), state.currentStep()));
    }
}
