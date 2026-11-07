package com.hanstep.onboarding.dto;

import com.hanstep.onboarding.domain.OnboardingStep;
import com.hanstep.onboarding.domain.OnboardingStepStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "온보딩 진행 상태(상단 진행 바·이어하기용)")
public record OnboardingResponse(
        @Schema(description = "6단계 모두 COMPLETED 또는 SKIPPED", example = "false")
        boolean completed,

        @Schema(description = "아직 끝나지 않은 첫 단계. completed=true 면 null", example = "MYDATA_CONSENT",
                nullable = true)
        OnboardingStep currentStep,

        @ArraySchema(arraySchema = @Schema(description = "6단계 항상 화면 순서대로 반환. 기록 없는 단계는 NOT_STARTED"))
        List<Step> steps,

        OnboardingProfileResponse profile
) {

    @Schema(name = "OnboardingStepItem", description = "단계별 상태")
    public record Step(
            @Schema(description = "단계 코드", example = "LANGUAGE")
            OnboardingStep stepCode,

            @Schema(description = "단계 상태", example = "COMPLETED")
            OnboardingStepStatus status,

            @Schema(description = "완료·건너뜀 시각(+09:00). 끝나지 않았으면 null",
                    example = "2026-11-07T10:02:11+09:00", nullable = true)
            OffsetDateTime completedAt
    ) {
    }
}
