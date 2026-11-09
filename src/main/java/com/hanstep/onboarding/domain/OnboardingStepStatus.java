package com.hanstep.onboarding.domain;

public enum OnboardingStepStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    SKIPPED;

    /** 완료 또는 건너뜀("나중에 연결할게요")이면 끝난 단계로 간주. */
    public boolean isDone() {
        return this == COMPLETED || this == SKIPPED;
    }
}
