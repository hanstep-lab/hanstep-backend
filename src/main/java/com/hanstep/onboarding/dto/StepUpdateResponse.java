package com.hanstep.onboarding.dto;

import com.hanstep.onboarding.domain.OnboardingStep;
import com.hanstep.onboarding.domain.OnboardingStepStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "단계 저장 결과와 다음 이동할 단계")
public record StepUpdateResponse(
        @Schema(description = "저장한 단계", example = "MYDATA_CONSENT")
        OnboardingStep stepCode,

        @Schema(description = "저장한 상태", example = "COMPLETED")
        OnboardingStepStatus status,

        @Schema(description = "완료·건너뜀 시각(+09:00)", example = "2026-11-07T10:05:30+09:00")
        OffsetDateTime completedAt,

        @Schema(description = "6단계 모두 끝났는지", example = "false")
        boolean onboardingCompleted,

        @Schema(description = "다음으로 보낼 단계(아직 끝나지 않은 첫 단계). 모두 끝났으면 null",
                example = "GOALS", nullable = true)
        OnboardingStep currentStep
) {
}
