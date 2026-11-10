package com.hanstep.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml 의 hanstep.internal.* 값.
 *
 * @param apiKey AI → Spring 내부 호출(/internal/v1/**) 검증 키. .env 의 SPRING_INTERNAL_API_KEY(AI 서버와 같은 값).
 *               비어 있거나 32자 미만이면 내부 호출을 모두 거부한다.
 */
@ConfigurationProperties(prefix = "hanstep.internal")
public record InternalApiProperties(String apiKey) {
}
