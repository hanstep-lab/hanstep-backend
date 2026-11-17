package com.hanstep.fx.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * application.yaml 의 hanstep.fx-collector.* 값. 환율 수집기(#119). 외부 환율 API 를 부르는 곳은 이 수집기뿐이다.
 * 1시간 주기 수집을 켜는 hanstep.fx-collector.enabled 는 스케줄러 조건(@ConditionalOnProperty)에서만 읽는다.
 *
 * @param currencies     수집할 통화(.env 의 FX_COLLECTOR_CURRENCIES, 쉼표). 비우면 USD,PHP.
 *                       PHP 는 Frankfurter(ECB), 나머지는 수출입은행 고시 통화만 받는다
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout    응답 전체 타임아웃
 * @param koreaexim      한국수출입은행 현재환율 Open API
 * @param frankfurter    Frankfurter v2(기관 ECB 고정)
 * @param backfill       백필(POST /internal/v1/fx/backfill) 기본값
 */
@ConfigurationProperties(prefix = "hanstep.fx-collector")
public record FxCollectorProperties(
        @DefaultValue("USD,PHP") String currencies,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue Koreaexim koreaexim,
        @DefaultValue Frankfurter frankfurter,
        @DefaultValue Backfill backfill) {

    static final String DEFAULT_CURRENCIES = "USD,PHP";
    static final String DEFAULT_FRANKFURTER_URL = "https://api.frankfurter.dev/v2";

    /**
     * @param baseUrl 기본 https://oapi.koreaexim.go.kr (테스트는 가짜 서버)
     * @param apiKey  인증키(.env 의 KOREAEXIM_API_KEY). 코드·문서·로그에 쓰지 않는다
     */
    public record Koreaexim(@DefaultValue("https://oapi.koreaexim.go.kr") String baseUrl, String apiKey) {

        public boolean hasApiKey() {
            return StringUtils.hasText(apiKey);
        }
    }

    /** @param baseUrl .env 의 FRANKFURTER_BASE_URL. 비우면 https://api.frankfurter.dev/v2 */
    public record Frankfurter(String baseUrl) {

        public String baseUrlOrDefault() {
            return StringUtils.hasText(baseUrl) ? baseUrl : DEFAULT_FRANKFURTER_URL;
        }
    }

    /**
     * @param months       기간을 안 주면 오늘(KST)부터 거슬러 올라갈 개월 수
     * @param maxCalls     수출입은행 호출 상한(1회 실행). 한도는 1000회/일로 알려져 있으나 미확인이라 400 으로 둔다.
     *                     넘으면 거기서 멈추고 nextFrom 을 돌려준다
     * @param callInterval 수출입은행 호출 사이 간격
     */
    public record Backfill(@DefaultValue("12") int months, @DefaultValue("400") int maxCalls,
                           @DefaultValue("300ms") Duration callInterval) {
    }

    /** 대문자로 바꾸고 중복·빈 값은 뺀다. 비우면 USD,PHP. */
    public List<String> currencyList() {
        String raw = StringUtils.hasText(currencies) ? currencies : DEFAULT_CURRENCIES;
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(StringUtils::hasText)
                .map(c -> c.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
