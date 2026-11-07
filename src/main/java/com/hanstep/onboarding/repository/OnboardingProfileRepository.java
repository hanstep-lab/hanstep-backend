package com.hanstep.onboarding.repository;

import com.hanstep.onboarding.domain.OnboardingProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingProfileRepository extends JpaRepository<OnboardingProfile, Long> {

    Optional<OnboardingProfile> findByUserId(Long userId);
}
