package com.hanstep.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** onboarding_profiles 테이블. 1인 1행(UNIQUE user_id). */
@Entity
@Table(name = "onboarding_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OnboardingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "residence_purpose", columnDefinition = "enum('WORK','STUDY','FAMILY','OTHER')")
    private ResidencePurpose residencePurpose;

    // 자기신고 월소득(KRW 고정, 통화 칸 없음)
    @Column(name = "declared_monthly_income", precision = 18, scale = 2)
    private BigDecimal declaredMonthlyIncome;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public OnboardingProfile(Long userId) {
        this.userId = userId;
    }

    /** ONB-02: 보낸 값으로 전체를 덮어쓴다(null 은 비움). */
    public void update(ResidencePurpose residencePurpose, BigDecimal declaredMonthlyIncome) {
        this.residencePurpose = residencePurpose;
        this.declaredMonthlyIncome = declaredMonthlyIncome;
    }
}
