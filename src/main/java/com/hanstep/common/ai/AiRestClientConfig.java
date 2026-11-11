package com.hanstep.common.ai;

import com.hanstep.common.security.InternalApiProperties;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.web.RequestIdFilter;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Spring → AI 호출용 RestClient(aiRestClient). AI 의 /internal/v1/** 는 X-Internal-Token 이 맞아야 통과한다.
 * 토큰이 없거나 짧아도 서버는 뜬다(AI 를 안 띄우는 개발 환경). 대신 시작할 때 경고를 남긴다.
 */
@Configuration
public class AiRestClientConfig {

    // AI 서버가 요구하는 최소 길이(AI config MIN_INTERNAL_TOKEN_LENGTH)
    private static final int MIN_TOKEN_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(AiRestClientConfig.class);

    @Bean
    public RestClient aiRestClient(RestClient.Builder builder, AiApiProperties ai, InternalApiProperties internal) {
        warnIfTokenUnusable(ai.internalToken(), internal.apiKey());
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.jdk()
                // AI(uvicorn)는 HTTP/2 를 받지 않는다. JDK 기본값인 h2c 업그레이드 시도를 끈다
                .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1))
                .build(HttpClientSettings.defaults().withTimeouts(ai.connectTimeout(), ai.readTimeout()));

        builder.requestFactory(requestFactory).baseUrl(ai.baseUrl());
        if (StringUtils.hasText(ai.internalToken())) {
            builder.defaultHeader(InternalTokenFilter.HEADER, ai.internalToken());
        }
        // 지금 처리 중인 요청의 ID 를 넘겨 AI 로그와 이어지게 한다
        return builder.requestInterceptor((request, body, execution) -> {
            String requestId = RequestIdFilter.current();
            if (requestId != null) {
                request.getHeaders().set(RequestIdFilter.HEADER, requestId);
            }
            return execution.execute(request, body);
        }).build();
    }

    private static void warnIfTokenUnusable(String token, String springInternalKey) {
        if (token == null || token.isBlank() || token.length() < MIN_TOKEN_LENGTH) {
            log.warn("AI_INTERNAL_API_TOKEN is missing or shorter than {} chars; AI rejects all Spring -> AI calls (401)",
                    MIN_TOKEN_LENGTH);
        } else if (token.equals(springInternalKey)) {
            log.warn("AI_INTERNAL_API_TOKEN must differ from SPRING_INTERNAL_API_KEY");
        }
    }
}
