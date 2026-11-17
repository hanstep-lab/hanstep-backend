package com.hanstep.user.dto;

import com.hanstep.onboarding.domain.OnboardingState;
import com.hanstep.onboarding.domain.OnboardingStep;
import com.hanstep.user.domain.Language;
import com.hanstep.user.domain.User;
import com.hanstep.user.domain.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;

@Schema(description = "내 정보 + 온보딩 진행 상태")
public record UserMeResponse(
        @Schema(example = "1")
        Long id,

        @Schema(description = "표시 이름(영문)", example = "MARK ANTHONY REYES")
        String displayName,

        @Schema(description = "짧은 이름(인사말용). displayName 첫 단어를 첫 글자만 대문자로 바꾼 값(임시 규칙). "
                + "displayName 이 비어 있으면 null", example = "Mark", nullable = true)
        String shortName,

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
                shortNameOf(user.getDisplayName()),
                user.getPreferredLanguage(),
                user.getStatus(),
                new Onboarding(state.completed(), state.currentStep()));
    }

    /**
     * 임시 규칙: 공백으로 나눈 첫 단어를 첫 글자만 대문자, 나머지는 소문자로("MARK ANTHONY REYES" → "Mark").
     * null·빈 값·공백뿐이면 null. 이름 순서(성이 앞인 경우 등)는 고려하지 않는다.
     */
    public static String shortNameOf(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String first = displayName.strip().split("\\s+", 2)[0];
        int end = first.offsetByCodePoints(0, 1);
        return first.substring(0, end).toUpperCase(Locale.ROOT) + first.substring(end).toLowerCase(Locale.ROOT);
    }
}
