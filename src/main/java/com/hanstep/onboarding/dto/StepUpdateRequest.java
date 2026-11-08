package com.hanstep.onboarding.dto;

import com.hanstep.onboarding.domain.OnboardingStepStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "단계 완료 또는 건너뛰기. ON-003A '나중에 연결할게요' = MYDATA_CONSENT 를 SKIPPED")
public record StepUpdateRequest(
        @Schema(description = "COMPLETED 또는 SKIPPED 만 허용(그 밖의 값은 400)", example = "COMPLETED",
                allowableValues = {"COMPLETED", "SKIPPED"})
        @NotNull
        OnboardingStepStatus status
) {
}
