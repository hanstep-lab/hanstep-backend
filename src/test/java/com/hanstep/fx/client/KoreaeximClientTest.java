package com.hanstep.fx.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanstep.fx.client.FxFetch.Rejection;
import com.hanstep.fx.config.FxCollectorProperties;
import com.hanstep.fx.config.FxRestClientConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 수출입은행 응답 파싱·오류 변환·인증키 가림. 로컬 HTTP 서버를 원천 대신 띄운다. */
class KoreaeximClientTest {

    static final String KEY = "test-koreaexim-key-0123456789ab";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 13);
    // 11/17 실제 응답에서 항목 4개만 남긴 것(cur_nm 등 쓰지 않는 칸 포함)
    static final String NORMAL = """
            [{"result":1,"cur_unit":"IDR(100)","ttb":"7.23","tts":"7.38","deal_bas_r":"7.31","bkpr":"7","cur_nm":"인도네시아 루피아"},
             {"result":1,"cur_unit":"JPY(100)","ttb":"825.11","tts":"841.78","deal_bas_r":"833.45","bkpr":"833","cur_nm":"일본 옌"},
             {"result":1,"cur_unit":"KRW","ttb":"0","tts":"0","deal_bas_r":"1","bkpr":"1","cur_nm":"한국 원"},
             {"result":1,"cur_unit":"USD","ttb":"1,319.07","tts":"1,345.72","deal_bas_r":"1,332.4","bkpr":"1,332","cur_nm":"미국 달러"}]""";

    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String contentType = "application/json;charset=UTF-8";
    private volatile String body = NORMAL;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(KoreaeximClient.PATH, exchange -> {
            requests.incrementAndGet();
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void 쉼표_숫자를_풀고_고시_단위를_나누고_KRW와_요청_안_한_통화는_뺀다() {
        FxFetch fetch = client(baseUrl(), KEY).fetch(DATE, List.of("USD", "JPY"));

        assertThat(query.get()).isEqualTo("authkey=" + KEY + "&searchdate=20261113&data=AP01");
        assertThat(fetch.rejections()).isEmpty();
        assertThat(fetch.rates()).extracting(FxRate::currency).containsExactly("JPY", "USD");
        FxRate usd = fetch.rates().get(1);
        assertThat(usd.unitAmount()).isEqualTo(1);
        assertThat(usd.baseRate()).isEqualByComparingTo("1332.4");
        assertThat(usd.sendRate()).isEqualByComparingTo("1345.72");
        assertThat(usd.receiveRate()).isEqualByComparingTo("1319.07");
        assertThat(usd.rateDate()).isEqualTo(DATE);
        // 시각을 주지 않아 기준일 00:00 KST
        assertThat(usd.rateAt()).isEqualTo(Instant.parse("2026-11-12T15:00:00Z"));
        assertThat(fetch.rates().get(0).unitAmount()).isEqualTo(100);
        assertThat(fetch.rates().get(0).baseRate()).isEqualByComparingTo("833.45");
    }

    @Test
    void 빈_배열은_새_값_없음() {
        body = "[]";

        assertThat(client(baseUrl(), KEY).fetch(DATE, List.of("USD"))).isEqualTo(FxFetch.EMPTY);
    }

    @Test
    void 인증키_오류_result_3은_더_부르지_않는_실패() {
        body = """
                [{"result":3,"cur_unit":null,"ttb":null,"tts":null,"deal_bas_r":null,"bkpr":null,"cur_nm":null}]""";

        assertThatThrownBy(() -> client(baseUrl(), KEY).fetch(DATE, List.of("USD")))
                .isInstanceOfSatisfying(FxSourceException.class, e -> {
                    assertThat(e.reason()).isEqualTo("result=3 (invalid auth key)");
                    assertThat(e.isFatal()).isTrue();
                });
    }

    @Test
    void 일일_한도_result_4도_더_부르지_않는_실패() {
        body = "[{\"result\":4}]";

        assertThatThrownBy(() -> client(baseUrl(), KEY).fetch(DATE, List.of("USD")))
                .isInstanceOfSatisfying(FxSourceException.class, e -> assertThat(e.isFatal()).isTrue());
    }

    @Test
    void HTTP_오류와_JSON이_아닌_응답은_실패() {
        status = 500;
        assertThatThrownBy(() -> client(baseUrl(), KEY).fetch(DATE, List.of("USD")))
                .isInstanceOfSatisfying(FxSourceException.class, e -> {
                    assertThat(e.reason()).isEqualTo("HTTP 500");
                    assertThat(e.isFatal()).isFalse();
                });

        status = 200;
        contentType = "text/html;charset=UTF-8";
        body = "<html>점검 중</html>";
        assertThatThrownBy(() -> client(baseUrl(), KEY).fetch(DATE, List.of("USD")))
                .isInstanceOfSatisfying(FxSourceException.class,
                        e -> assertThat(e.reason()).startsWith("bad response"));
    }

    @Test
    void 숫자가_아니거나_0_이하인_값은_저장하지_않는다() {
        body = """
                [{"result":1,"cur_unit":"USD","ttb":"-","tts":"1,345.72","deal_bas_r":"1,332.4"},
                 {"result":1,"cur_unit":"JPY(100)","ttb":"825.11","tts":"0","deal_bas_r":"833.45"},
                 {"result":1,"cur_unit":"EUR","ttb":"1,500.1","tts":"1,530.2","deal_bas_r":"-1"},
                 {"result":1,"cur_unit":"GBP","ttb":"1,700.12345","tts":"1,760.2","deal_bas_r":"1,730.1"}]""";

        FxFetch fetch = client(baseUrl(), KEY).fetch(DATE, List.of("USD", "JPY", "EUR", "GBP"));

        assertThat(fetch.rates()).isEmpty();
        assertThat(fetch.rejections()).extracting(Rejection::currency).containsExactly("USD", "JPY", "EUR", "GBP");
        assertThat(fetch.rejections().get(0).reason()).isEqualTo("not a number '-'");
        assertThat(fetch.rejections().get(1).reason()).startsWith("must be greater than 0");
        assertThat(fetch.rejections().get(3).reason()).startsWith("more than 4 decimal places");
    }

    @Test
    void 응답에_없는_통화는_이유와_함께_남긴다() {
        FxFetch fetch = client(baseUrl(), KEY).fetch(DATE, List.of("USD", "VND"));

        assertThat(fetch.rates()).extracting(FxRate::currency).containsExactly("USD");
        assertThat(fetch.rejections()).containsExactly(new Rejection("VND", "not in KOREAEXIM response"));
    }

    @Test
    void 인증키가_없으면_부르지_않는다() {
        assertThatThrownBy(() -> client(baseUrl(), "").fetch(DATE, List.of("USD")))
                .isInstanceOfSatisfying(FxSourceException.class, e -> {
                    assertThat(e.reason()).isEqualTo("KOREAEXIM_API_KEY is not set");
                    assertThat(e.isFatal()).isTrue();
                });
        assertThat(requests.get()).isZero();
    }

    @Test
    void 연결_실패_이유에_인증키가_나오지_않는다() {
        // 닫힌 포트. Spring 7 은 이 메시지에 쿼리를 넣지 않지만, 넣더라도 mask 가 가린다(아래 테스트)
        FxSourceException error = catchSource(() -> client("http://127.0.0.1:1", KEY).fetch(DATE, List.of("USD")));

        assertThat(error.reason()).startsWith("unreachable: ").doesNotContain(KEY);
        assertThat(error.getCause()).isNull();
        assertThat(error.getStackTrace()).isEmpty();
    }

    @Test
    void mask는_authkey_값만_가린다() {
        assertThat(FxSourceException.mask("GET https://x/y?authkey=" + KEY + "&searchdate=20261113"))
                .isEqualTo("GET https://x/y?authkey=****&searchdate=20261113");
        assertThat(FxSourceException.mask("\"https://x?AUTHKEY=" + KEY + "\": timeout"))
                .isEqualTo("\"https://x?AUTHKEY=****\": timeout");
    }

    static KoreaeximClient client(String baseUrl, String key) {
        FxCollectorProperties properties = properties(baseUrl, key, "http://127.0.0.1:1");
        return new KoreaeximClient(new FxRestClientConfig().koreaeximRestClient(RestClient.builder(), properties),
                properties);
    }

    static FxCollectorProperties properties(String koreaeximUrl, String key, String frankfurterUrl) {
        return new FxCollectorProperties("USD,PHP", Duration.ofSeconds(1), Duration.ofSeconds(2),
                new FxCollectorProperties.Koreaexim(koreaeximUrl, key),
                new FxCollectorProperties.Frankfurter(frankfurterUrl),
                new FxCollectorProperties.Backfill(12, 400, Duration.ZERO));
    }

    private static FxSourceException catchSource(Runnable call) {
        try {
            call.run();
        } catch (FxSourceException e) {
            return e;
        }
        throw new AssertionError("FxSourceException expected");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
