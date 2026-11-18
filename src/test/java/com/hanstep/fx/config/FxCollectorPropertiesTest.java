package com.hanstep.fx.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** FX_COLLECTOR_CURRENCIES·FRANKFURTER_BASE_URL 을 비워 두면(.env.example 그대로) 기본값을 쓴다. */
class FxCollectorPropertiesTest {

    @Test
    void 통화는_대문자로_바꾸고_중복을_빼며_비우면_USD_PHP() {
        assertThat(properties(" usd , php,USD,, ", null).currencyList()).containsExactly("USD", "PHP");
        assertThat(properties("", null).currencyList()).containsExactly("USD", "PHP");
        assertThat(properties(null, null).currencyList()).containsExactly("USD", "PHP");
    }

    @Test
    void Frankfurter_주소를_비우면_기본_주소() {
        assertThat(properties(null, "").frankfurter().baseUrlOrDefault()).isEqualTo("https://api.frankfurter.dev/v2");
        assertThat(properties(null, "http://127.0.0.1:9/v2").frankfurter().baseUrlOrDefault())
                .isEqualTo("http://127.0.0.1:9/v2");
    }

    private static FxCollectorProperties properties(String currencies, String frankfurterUrl) {
        return new FxCollectorProperties(currencies, Duration.ofSeconds(1), Duration.ofSeconds(1),
                new FxCollectorProperties.Koreaexim("https://oapi.koreaexim.go.kr", ""),
                new FxCollectorProperties.Frankfurter(frankfurterUrl),
                new FxCollectorProperties.Backfill(12, 400, Duration.ZERO));
    }
}
