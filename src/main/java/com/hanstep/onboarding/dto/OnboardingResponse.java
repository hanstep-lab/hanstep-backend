package com.hanstep.onboarding.dto;

import com.hanstep.onboarding.domain.OnboardingProgress;
import com.hanstep.onboarding.domain.OnboardingState;
import com.hanstep.onboarding.domain.OnboardingStep;
import com.hanstep.onboarding.domain.OnboardingStepStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /** 저장된 단계 행으로 6단계 전체를 만든다(행이 없는 단계는 NOT_STARTED). */
    public static OnboardingResponse of(List<OnboardingProgress> rows, OnboardingProfileResponse profile) {
        Map<OnboardingStep, OnboardingProgress> byStep = rows.stream()
                .collect(Collectors.toMap(OnboardingProgress::getStepCode, Function.identity()));
        List<Step> steps = Arrays.stream(OnboardingStep.values())
                .map(step -> {
                    OnboardingProgress row = byStep.get(step);
                    return row == null
                            ? new Step(step, OnboardingStepStatus.NOT_STARTED, null)
                            : new Step(step, row.getStatus(), toKst(row));
                })
                .toList();
        OnboardingState state = OnboardingState.from(rows);
        return new OnboardingResponse(state.completed(), state.currentStep(), steps, profile);
    }

    private static OffsetDateTime toKst(OnboardingProgress row) {
        return row.getCompletedAt() == null ? null : row.getCompletedAt().atOffset(ZoneOffset.ofHours(9));
    }

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
