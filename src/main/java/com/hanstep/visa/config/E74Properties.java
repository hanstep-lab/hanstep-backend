package com.hanstep.visa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml 의 hanstep.e74.* 값.
 *
 * @param includeDraftRules 승인 전(DRAFT) 규칙 버전으로 계산한 결과도 저장·반환할지. .env 의 E74_INCLUDE_DRAFT_RULES
 *                          (AI 서버 .env 와 같은 이름, 같은 값). 기본 false. 운영은 false, 로컬·시연에서만 true
 */
@ConfigurationProperties(prefix = "hanstep.e74")
public record E74Properties(boolean includeDraftRules) {
}
