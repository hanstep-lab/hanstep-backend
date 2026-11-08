package com.hanstep.onboarding.service;

import com.hanstep.commitment.service.CommitmentService;
import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.goal.service.GoalService;
import com.hanstep.onboarding.domain.OnboardingProfile;
import com.hanstep.onboarding.domain.OnboardingProgress;
import com.hanstep.onboarding.domain.OnboardingState;
import com.hanstep.onboarding.domain.OnboardingStep;
import com.hanstep.onboarding.domain.OnboardingStepStatus;
import com.hanstep.onboarding.dto.OnboardingProfileRequest;
import com.hanstep.onboarding.dto.OnboardingProfileResponse;
import com.hanstep.onboarding.dto.OnboardingResponse;
import com.hanstep.onboarding.dto.OnboardingSummaryResponse;
import com.hanstep.onboarding.dto.StepUpdateResponse;
import com.hanstep.onboarding.repository.OnboardingProfileRepository;
import com.hanstep.onboarding.repository.OnboardingProgressRepository;
import com.hanstep.user.domain.User;
import com.hanstep.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ONB-01 ~ 04. */
@Service
@Transactional(readOnly = true)
public class OnboardingService {

    private final OnboardingProgressRepository progressRepository;
    private final OnboardingProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final GoalService goalService;
    private final CommitmentService commitmentService;
    private final JdbcTemplate jdbcTemplate;

    public OnboardingService(OnboardingProgressRepository progressRepository,
                             OnboardingProfileRepository profileRepository,
                             UserRepository userRepository,
                             GoalService goalService,
                             CommitmentService commitmentService,
                             JdbcTemplate jdbcTemplate) {
        this.progressRepository = progressRepository;
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.goalService = goalService;
        this.commitmentService = commitmentService;
        this.jdbcTemplate = jdbcTemplate;
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

    /** ONB-03. COMPLETED·SKIPPED 만 저장하고 다음 단계를 돌려준다. */
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

    /** ONB-04. balance 는 FIN-05 연동 전까지 null. */
    public OnboardingSummaryResponse getSummary(Long userId) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        LocalDate today = LocalDate.now(KstDates.KST);

        // visa_profiles 는 OCR 담당 영역이라 엔티티 없이 읽기만 한다
        List<VisaRow> rows = jdbcTemplate.query("""
                        SELECT vt.code, vt.name_en, vp.stay_expires_on, vp.nationality_code
                        FROM visa_profiles vp LEFT JOIN visa_types vt ON vt.id = vp.visa_type_id
                        WHERE vp.user_id = ?""",
                (rs, i) -> new VisaRow(rs.getString("code"), rs.getString("name_en"),
                        rs.getObject("stay_expires_on", LocalDate.class), rs.getString("nationality_code")),
                userId);
        VisaRow row = rows.isEmpty() ? null : rows.get(0);

        OnboardingSummaryResponse.Visa visa = null;
        if (row != null && row.code() != null && row.stayExpiresOn() != null) {
            visa = new OnboardingSummaryResponse.Visa(row.code(), row.nameEn(), row.stayExpiresOn(),
                    ChronoUnit.DAYS.between(today, row.stayExpiresOn()),
                    KstDates.monthsUntil(today, row.stayExpiresOn()));
        }

        return new OnboardingSummaryResponse(
                new OnboardingSummaryResponse.User(user.getId(), user.getDisplayName(), user.getPreferredLanguage(),
                        row == null ? null : row.nationalityCode()),
                visa,
                goalService.getGoals(userId).goals(),
                commitmentService.getCommitments(userId),
                null);
    }

    private record VisaRow(String code, String nameEn, LocalDate stayExpiresOn, String nationalityCode) {
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, null,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
