package com.hanstep.user.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.onboarding.domain.OnboardingState;
import com.hanstep.onboarding.repository.OnboardingProgressRepository;
import com.hanstep.user.domain.Language;
import com.hanstep.user.domain.User;
import com.hanstep.user.dto.UserMeResponse;
import com.hanstep.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final OnboardingProgressRepository onboardingProgressRepository;

    public UserService(UserRepository userRepository,
                       OnboardingProgressRepository onboardingProgressRepository) {
        this.userRepository = userRepository;
        this.onboardingProgressRepository = onboardingProgressRepository;
    }

    /** USR-01. 토큰은 유효한데 사용자가 없거나 ACTIVE 가 아니면 401(다시 진입해야 함). */
    public UserMeResponse getMe(Long userId) {
        return describe(findActive(userId));
    }

    /** USR-02. */
    @Transactional
    public UserMeResponse changeLanguage(Long userId, Language language) {
        User user = findActive(userId);
        user.changeLanguage(language);
        return describe(user);
    }

    private User findActive(Long userId) {
        return userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    public UserMeResponse describe(User user) {
        OnboardingState state = OnboardingState.from(
                onboardingProgressRepository.findAllByUserId(user.getId()));
        return UserMeResponse.of(user, state);
    }
}
