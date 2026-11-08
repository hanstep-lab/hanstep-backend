package com.hanstep.auth.service;

import com.hanstep.auth.dto.EntryResponse;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.user.domain.User;
import com.hanstep.user.repository.UserRepository;
import com.hanstep.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AUTH-01 진입.
 *
 * <p>데모 가정: entryToken = users.external_user_key. 실서비스에서는 SOL 이 서명한 토큰을 검증한 뒤
 * 고객 식별값으로 사용자를 찾아야 함(지금 방식은 키를 아는 사람이면 누구나 진입 가능 → 데모 전용).
 * 사용자가 없을 때 새로 만들지 않음.
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final UserRepository userRepository;
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserRepository userRepository, UserService userService,
                       JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public EntryResponse enter(String entryToken) {
        User user = userRepository.findByExternalUserKey(entryToken.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "unknown entry token", null));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "user is not active", null);
        }
        String accessToken = jwtTokenProvider.issue(user.getId());
        return new EntryResponse(accessToken, TOKEN_TYPE, jwtTokenProvider.getExpiresInSeconds(),
                userService.describe(user));
    }
}
