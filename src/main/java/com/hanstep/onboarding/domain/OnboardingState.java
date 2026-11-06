package com.hanstep.onboarding.domain;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * 온보딩 전체 상태(계산값, 저장하지 않음).
 *
 * @param completed   6단계가 모두 COMPLETED 또는 SKIPPED
 * @param currentStep 아직 끝나지 않은 첫 단계. 모두 끝났으면 null
 */
public record OnboardingState(boolean completed, OnboardingStep currentStep) {

    public static OnboardingState from(Collection<OnboardingProgress> rows) {
        Map<OnboardingStep, OnboardingStepStatus> statuses = new EnumMap<>(OnboardingStep.class);
        for (OnboardingProgress row : rows) {
            statuses.put(row.getStepCode(), row.getStatus());
        }
        return from(statuses);
    }

    public static OnboardingState from(Map<OnboardingStep, OnboardingStepStatus> statuses) {
        for (OnboardingStep step : OnboardingStep.values()) {
            OnboardingStepStatus status = statuses.getOrDefault(step, OnboardingStepStatus.NOT_STARTED);
            if (!status.isDone()) {
                return new OnboardingState(false, step);
            }
        }
        return new OnboardingState(true, null);
    }
}
