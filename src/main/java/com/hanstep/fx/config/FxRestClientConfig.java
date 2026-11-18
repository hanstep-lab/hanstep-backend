package com.hanstep.fx.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 환율 원천 호출용 RestClient. AI 호출용(aiRestClient)과 달리 내부 토큰·요청 ID 를 붙이지 않는다.
 * 요청 URL 은 로그에 남기지 않는다(수출입은행 인증키가 쿼리에 들어감. 오류 문구는 FxSourceException 에서 가린다).
 */
@Configuration
public class FxRestClientConfig {

    @Bean
    public RestClient koreaeximRestClient(RestClient.Builder builder, FxCollectorProperties properties) {
        return build(builder, properties, properties.koreaexim().baseUrl());
    }

    @Bean
    public RestClient frankfurterRestClient(RestClient.Builder builder, FxCollectorProperties properties) {
        return build(builder, properties, properties.frankfurter().baseUrlOrDefault());
    }

    private static RestClient build(RestClient.Builder builder, FxCollectorProperties properties, String baseUrl) {
        return builder.clone()
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                        .build(HttpClientSettings.defaults()
                                .withTimeouts(properties.connectTimeout(), properties.readTimeout())))
                .baseUrl(baseUrl)
                .build();
    }
}
