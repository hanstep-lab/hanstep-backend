package com.hanstep.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yaml 의 hanstep.chat.* 값.
 *
 * @param streamExecutor CHT-04 에서 AI 스트림을 읽는 실행기. virtual(가상 스레드, 기본) | platform(고정 풀)
 * @param streamPoolSize platform 일 때 고정 풀 크기(큐는 제한 없음). 기본 16
 */
@ConfigurationProperties(prefix = "hanstep.chat")
public record ChatProperties(@DefaultValue("virtual") StreamExecutor streamExecutor,
                             @DefaultValue("16") int streamPoolSize) {

    public enum StreamExecutor {
        PLATFORM,
        VIRTUAL
    }
}
