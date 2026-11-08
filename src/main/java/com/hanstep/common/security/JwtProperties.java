package com.hanstep.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml 의 hanstep.jwt.* 값.
 *
 * @param secret           HMAC 서명 키(Base64, 디코딩 후 32바이트 이상). .env 의 JWT_SECRET
 * @param expiresInSeconds 액세스 토큰 유효 시간(초). .env 의 JWT_EXPIRES_IN_SECONDS, 기본 3600
 */
@ConfigurationProperties(prefix = "hanstep.jwt")
public record JwtProperties(String secret, long expiresInSeconds) {
}
