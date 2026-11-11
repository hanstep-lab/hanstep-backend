package com.hanstep.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /internal/v1/** 전용. X-Internal-Token 이 설정된 키와 같으면 내부 호출자로 인증한다.
 * 틀리거나 없으면 인증하지 않고 넘겨서 SecurityErrorHandler 가 401 을 응답한다.
 * 빈 등록(@Component) 하지 않는다. 서블릿 필터로 자동 등록되면 모든 요청에 걸리기 때문.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    public static final String ROLE = "ROLE_INTERNAL";
    static final int MIN_KEY_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);

    /** 키 SHA-256. 키가 없거나 짧으면 null(모든 내부 호출 거부). */
    private final byte[] expectedDigest;

    public InternalTokenFilter(InternalApiProperties properties) {
        String key = properties.apiKey();
        if (key == null || key.isBlank() || key.length() < MIN_KEY_LENGTH) {
            log.warn("SPRING_INTERNAL_API_KEY is missing or shorter than {} chars; all /internal/v1 calls are rejected",
                    MIN_KEY_LENGTH);
            this.expectedDigest = null;
        } else {
            this.expectedDigest = sha256(key);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (expectedDigest != null && token != null && MessageDigest.isEqual(expectedDigest, sha256(token))) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "internal", null, List.of(new SimpleGrantedAuthority(ROLE)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    // 길이가 달라도 같은 길이로 비교되게 해시를 비교한다
    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
