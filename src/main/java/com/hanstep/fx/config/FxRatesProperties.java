package com.hanstep.fx.config;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml 의 hanstep.fx-rates.* 값. 환율 조회(화면 FX-001·송금 견적·챗봇 INT-14)가 같이 쓴다(#123).
 *
 * @param includeMock    Mock 환율(04 시드, is_mock = TRUE)도 읽을지. 운영은 false(실데이터만, 11/17 결정).
 *                       로컬 개발에서만 .env 의 FX_INCLUDE_MOCK_RATES=true. 비우면 false
 * @param homeCurrencies 화면 통화를 정하는 국적(visa_profiles.nationality_code, ISO 3166-1 alpha-3) → 모국 통화
 */
@ConfigurationProperties(prefix = "hanstep.fx-rates")
public record FxRatesProperties(Boolean includeMock, List<HomeCurrency> homeCurrencies) {

    public record HomeCurrency(String nationality, String currency) {
    }

    public boolean mockIncluded() {
        return Boolean.TRUE.equals(includeMock);
    }

    /** 국적의 모국 통화. 국적이 없거나 매핑이 없으면 빈 값. */
    public Optional<String> homeCurrency(String nationality) {
        if (nationality == null || homeCurrencies == null) {
            return Optional.empty();
        }
        return homeCurrencies.stream().filter(h -> nationality.equals(h.nationality()))
                .map(HomeCurrency::currency).findFirst();
    }
}
