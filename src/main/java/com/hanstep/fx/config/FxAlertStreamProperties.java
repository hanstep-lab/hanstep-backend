package com.hanstep.fx.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yaml 의 hanstep.fx-alert-stream.* 값(목표 환율 도달 스트림 GET /api/v1/fx/alerts/stream, #129).
 *
 * @param pingInterval          keep-alive 주석(": ping") 간격. 프록시·로드밸런서가 조용한 연결을 끊지 않게. 임시값 25s
 * @param timeout               연결 1개를 열어 두는 최대 시간(SseEmitter 타임아웃). 지나면 서버가 닫고 앱이 다시 연결한다. 임시값 30m
 * @param maxConnectionsPerUser 한 사용자의 동시 연결 수(여러 기기). 넘으면 가장 오래된 연결을 닫는다. 기본 3
 */
@ConfigurationProperties(prefix = "hanstep.fx-alert-stream")
public record FxAlertStreamProperties(@DefaultValue("25s") Duration pingInterval,
                                      @DefaultValue("30m") Duration timeout,
                                      @DefaultValue("3") int maxConnectionsPerUser) {
}
