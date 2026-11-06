package com.hanstep.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * onboarding_progress 테이블. 사용자·단계마다 1행(UNIQUE user_id, step_code).
 * 행이 없는 단계는 NOT_STARTED 로 본다. 단계 저장(ONB-03)은 온보딩 BE 작업에서 추가한다.
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

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
