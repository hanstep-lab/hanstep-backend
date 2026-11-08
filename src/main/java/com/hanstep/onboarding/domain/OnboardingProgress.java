package com.hanstep.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * onboarding_progress 테이블. 사용자·단계마다 1행(UNIQUE user_id, step_code).
 * 행이 없는 단계는 NOT_STARTED 로 간주.
 */
@Entity
@Table(name = "onboarding_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OnboardingProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_code", nullable = false, columnDefinition =
            "enum('LANGUAGE','ID_DOCUMENT','MYDATA_CONSENT','GOALS','GOAL_DETAILS','SUMMARY_CONFIRM')")
    private OnboardingStep stepCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition =
            "enum('NOT_STARTED','IN_PROGRESS','COMPLETED','SKIPPED')")
    private OnboardingStepStatus status;

    // DB 는 UTC(hibernate.jdbc.time_zone). 응답 때 +09:00 으로 바꿈
    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public OnboardingProgress(Long userId, OnboardingStep stepCode) {
        this.userId = userId;
        this.stepCode = stepCode;
        this.status = OnboardingStepStatus.NOT_STARTED;
    }

    /** 완료 또는 건너뜀. 다시 보내면 상태·시각을 덮어씀. */
    public void finish(OnboardingStepStatus status, Instant at) {
        this.status = status;
        this.completedAt = at;
    }
}
