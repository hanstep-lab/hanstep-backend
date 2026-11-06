package com.hanstep.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OnboardingStateTest {

    @Test
    void 기록이_없으면_첫_단계부터() {
        OnboardingState state = OnboardingState.from(Map.of());

        assertThat(state.completed()).isFalse();
        assertThat(state.currentStep()).isEqualTo(OnboardingStep.LANGUAGE);
    }

    @Test
    void 끝나지_않은_첫_단계를_현재_단계로() {
        Map<OnboardingStep, OnboardingStepStatus> statuses = new EnumMap<>(OnboardingStep.class);
        statuses.put(OnboardingStep.LANGUAGE, OnboardingStepStatus.COMPLETED);
        statuses.put(OnboardingStep.ID_DOCUMENT, OnboardingStepStatus.COMPLETED);
        statuses.put(OnboardingStep.MYDATA_CONSENT, OnboardingStepStatus.SKIPPED);
        statuses.put(OnboardingStep.GOALS, OnboardingStepStatus.IN_PROGRESS);

        OnboardingState state = OnboardingState.from(statuses);

        assertThat(state.completed()).isFalse();
        assertThat(state.currentStep()).isEqualTo(OnboardingStep.GOALS);
    }

    @Test
    void 모두_완료_또는_건너뜀이면_완료() {
        Map<OnboardingStep, OnboardingStepStatus> statuses = new EnumMap<>(OnboardingStep.class);
        for (OnboardingStep step : OnboardingStep.values()) {
            statuses.put(step, OnboardingStepStatus.COMPLETED);
        }
        statuses.put(OnboardingStep.MYDATA_CONSENT, OnboardingStepStatus.SKIPPED);

        OnboardingState state = OnboardingState.from(statuses);

        assertThat(state.completed()).isTrue();
        assertThat(state.currentStep()).isNull();
    }
}
