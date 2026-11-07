package com.hanstep.onboarding.repository;

import com.hanstep.onboarding.domain.OnboardingProgress;
import com.hanstep.onboarding.domain.OnboardingStep;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingProgressRepository extends JpaRepository<OnboardingProgress, Long> {

    List<OnboardingProgress> findAllByUserId(Long userId);

    Optional<OnboardingProgress> findByUserIdAndStepCode(Long userId, OnboardingStep stepCode);
}
