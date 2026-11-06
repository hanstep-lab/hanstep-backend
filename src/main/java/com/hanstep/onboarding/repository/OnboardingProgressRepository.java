package com.hanstep.onboarding.repository;

import com.hanstep.onboarding.domain.OnboardingProgress;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingProgressRepository extends JpaRepository<OnboardingProgress, Long> {

    List<OnboardingProgress> findAllByUserId(Long userId);
}
