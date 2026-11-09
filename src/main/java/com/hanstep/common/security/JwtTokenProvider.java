package com.hanstep.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * JWT 발급·검증.
 * 토큰에는 사용자 ID(sub)만 담음. 이름·국적 같은 개인정보는 넣지 않음(토큰은 Base64라 누구나 읽을 수 있음).
 */
@Component
public class JwtTokenProvider {

    static final String ISSUER = "hanstep";
    private static final int MIN_KEY_BYTES = 32; // HS256 최소 256비트

    private final SecretKey key;
    private final long expiresInSeconds;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = toKey(properties.secret());
        if (properties.expiresInSeconds() <= 0) {
            throw new IllegalStateException("hanstep.jwt.expires-in-seconds must be positive");
        }
        this.expiresInSeconds = properties.expiresInSeconds();
    }

    public String issue(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expiresInSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * 서명·만료·발급자를 검사하고 사용자 ID를 꺼냄. 하나라도 틀리면 빈 값.
     */
    public Optional<Long> parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            // 만료, 서명 불일치, 형식 오류, sub 가 숫자가 아님 → 모두 인증 실패로 처리
            return Optional.empty();
        }
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    private static SecretKey toKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set. See .env.example");
        }
        byte[] bytes;
        try {
            bytes = Decoders.BASE64.decode(secret.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("JWT_SECRET must be a Base64 string. See .env.example", e);
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_KEY_BYTES + " bytes after Base64 decoding");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
