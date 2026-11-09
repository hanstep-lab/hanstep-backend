package com.hanstep.onboarding.service;

import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.onboarding.domain.OnboardingProfile;
import com.hanstep.onboarding.domain.OnboardingProgress;
import com.hanstep.onboarding.domain.OnboardingState;
import com.hanstep.onboarding.domain.OnboardingStep;
import com.hanstep.onboarding.domain.OnboardingStepStatus;
import com.hanstep.onboarding.dto.OnboardingProfileRequest;
import com.hanstep.onboarding.dto.OnboardingProfileResponse;
import com.hanstep.onboarding.dto.OnboardingResponse;
import com.hanstep.onboarding.dto.StepUpdateResponse;
import com.hanstep.onboarding.repository.OnboardingProfileRepository;
import com.hanstep.onboarding.repository.OnboardingProgressRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ONB-01 ~ 03. */
@Service
@Transactional(readOnly = true)
public class OnboardingService {

    private final OnboardingProgressRepository progressRepository;
    private final OnboardingProfileRepository profileRepository;

    public OnboardingService(OnboardingProgressRepository progressRepository,
                             OnboardingProfileRepository profileRepository) {
        this.progressRepository = progressRepository;
        this.profileRepository = profileRepository;
    }

    /** ONB-01. */
    public OnboardingResponse getOnboarding(Long userId) {
        OnboardingProfileResponse profile = profileRepository.findByUserId(userId)
                .map(OnboardingProfileResponse::from)
                .orElseGet(OnboardingProfileResponse::empty);
        return OnboardingResponse.of(progressRepository.findAllByUserId(userId), profile);
    }

    /** ONB-02. 1인 1행 UPSERT. */
    @Transactional
    public OnboardingProfileResponse saveProfile(Long userId, OnboardingProfileRequest request) {
        Money income = request.declaredMonthlyIncome();
        if (income != null && !Money.KRW.equals(income.currency())) {
            throw invalid("declaredMonthlyIncome.currency", "must be KRW");
        }
        OnboardingProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> new OnboardingProfile(userId));
        profile.update(request.residencePurpose(), income == null ? null : BigDecimal.valueOf(income.amount()));
        return OnboardingProfileResponse.from(profileRepository.save(profile));
    }

    /** ONB-03. COMPLETED·SKIPPED 만 저장하고 다음 단계를 돌려줌. */
    @Transactional
    public StepUpdateResponse finishStep(Long userId, OnboardingStep step, OnboardingStepStatus status) {
        if (!status.isDone()) {
            throw invalid("status", "must be COMPLETED or SKIPPED");
        }
        OnboardingProgress row = progressRepository.findByUserIdAndStepCode(userId, step)
                .orElseGet(() -> new OnboardingProgress(userId, step));
        row.finish(status, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        progressRepository.save(row);
        return StepUpdateResponse.of(row, OnboardingState.from(progressRepository.findAllByUserId(userId)));
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, null,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
