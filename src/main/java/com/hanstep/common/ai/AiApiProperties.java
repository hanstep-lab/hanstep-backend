package com.hanstep.common.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml 의 hanstep.ai.* 값. Spring → AI 내부 호출(AI 서버의 /internal/v1/**) 설정.
 *
 * @param baseUrl        AI 서버 주소. .env 의 AI_INTERNAL_API_URL, 기본 http://localhost:8000
 * @param internalToken  X-Internal-Token 으로 보내는 값. .env 의 AI_INTERNAL_API_TOKEN(AI 서버 .env 와 같은 값).
 *                       SPRING_INTERNAL_API_KEY 와 다른 값을 쓴다. 비었거나 32자 미만이면 AI 가 모두 401 로 거부한다.
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout    응답 타임아웃. AI 정책 검색 자체 제한(20초)보다 길게 둔다
 */
@ConfigurationProperties(prefix = "hanstep.ai")
public record AiApiProperties(String baseUrl, String internalToken, Duration connectTimeout, Duration readTimeout) {
}
