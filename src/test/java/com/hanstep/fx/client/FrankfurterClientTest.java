package com.hanstep.fx.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanstep.fx.config.FxCollectorProperties;
import com.hanstep.fx.config.FxRestClientConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Frankfurter(ECB) 응답 파싱·고시 시각·오류 변환. 로컬 HTTP 서버를 원천 대신 띄운다. */
class FrankfurterClientTest {

    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private volatile int status = 200;
    // 11/17 실제 응답과 같은 형식
    private volatile String body = "[{\"date\":\"2026-11-16\",\"base\":\"PHP\",\"quote\":\"KRW\",\"rate\":20.872}]";
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
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
    void 최신값은_ECB_고정_경로로_받고_환율을_반올림하지_않는다() {
        FxFetch fetch = client().latest("PHP");

        assertThat(path.get()).isEqualTo("/v2/providers/ecb/rates");
        assertThat(query.get()).isEqualTo("base=PHP&quotes=KRW");
        assertThat(fetch.rejections()).isEmpty();
        FxRate php = fetch.rates().getFirst();
        assertThat(php.currency()).isEqualTo("PHP");
        assertThat(php.unitAmount()).isEqualTo(1);
        assertThat(php.baseRate().toPlainString()).isEqualTo("20.872");
        assertThat(php.sendRate()).isNull();
        assertThat(php.receiveRate()).isNull();
        assertThat(php.rateDate()).isEqualTo(LocalDate.of(2026, 11, 16));
        // ECB 고시 16:00 CET(겨울) = 15:00 UTC
        assertThat(php.rateAt()).isEqualTo(Instant.parse("2026-11-16T15:00:00Z"));
    }

    @Test
    void 고시_시각은_서머타임을_반영한다() {
        // 16:00 CEST = 14:00 UTC. 서머타임 시작(3/29)·끝(10/25) 전후
        assertThat(FrankfurterClient.rateAt(LocalDate.of(2026, 7, 15))).isEqualTo(Instant.parse("2026-07-15T14:00:00Z"));
        assertThat(FrankfurterClient.rateAt(LocalDate.of(2026, 3, 27))).isEqualTo(Instant.parse("2026-03-27T15:00:00Z"));
        assertThat(FrankfurterClient.rateAt(LocalDate.of(2026, 3, 30))).isEqualTo(Instant.parse("2026-03-30T14:00:00Z"));
        assertThat(FrankfurterClient.rateAt(LocalDate.of(2026, 10, 26))).isEqualTo(Instant.parse("2026-10-26T15:00:00Z"));
    }

    @Test
    void 기간은_from_to로_한_번에_받는다() {
        body = """
                [{"date":"2026-11-12","base":"PHP","quote":"KRW","rate":20.935},
                 {"date":"2026-11-13","base":"PHP","quote":"KRW","rate":20.954}]""";

        FxFetch fetch = client().range("PHP", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 15));

        assertThat(query.get()).isEqualTo("base=PHP&quotes=KRW&from=2026-11-12&to=2026-11-15");
        assertThat(fetch.rates()).extracting(FxRate::rateDate)
                .containsExactly(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13));
    }

    @Test
    void 빈_배열은_새_값_없음() {
        body = "[]";

        assertThat(client().latest("PHP")).isEqualTo(FxFetch.EMPTY);
    }

    @Test
    void 요청과_다른_통화가_오거나_HTTP_오류면_실패() {
        body = "[{\"date\":\"2026-11-16\",\"base\":\"EUR\",\"quote\":\"KRW\",\"rate\":1500.1}]";
        assertThatThrownBy(() -> client().latest("PHP"))
                .isInstanceOfSatisfying(FxSourceException.class, e -> assertThat(e.reason()).startsWith("unexpected item"));

        status = 404;
        body = "{\"message\":\"not found\"}";
        assertThatThrownBy(() -> client().latest("PHP"))
                .isInstanceOfSatisfying(FxSourceException.class, e -> {
                    assertThat(e.reason()).isEqualTo("HTTP 404");
                    assertThat(e.isFatal()).isFalse();
                });
    }

    @Test
    void 연결_실패도_실패로_바꾼다() {
        FxCollectorProperties properties = KoreaeximClientTest.properties("http://127.0.0.1:1", "", "http://127.0.0.1:1");
        FrankfurterClient closed = new FrankfurterClient(
                new FxRestClientConfig().frankfurterRestClient(RestClient.builder(), properties));

        assertThatThrownBy(() -> closed.latest("PHP"))
                .isInstanceOfSatisfying(FxSourceException.class, e -> assertThat(e.reason()).startsWith("unreachable"));
    }

    @Test
    void 영_이하거나_소수_5자리_이상인_환율은_저장하지_않는다() {
        body = """
                [{"date":"2026-11-12","base":"PHP","quote":"KRW","rate":0},
                 {"date":"2026-11-13","base":"PHP","quote":"KRW","rate":20.95412},
                 {"date":"2026-11-16","base":"PHP","quote":"KRW","rate":20.872}]""";

        FxFetch fetch = client().latest("PHP");

        assertThat(fetch.rates()).extracting(FxRate::rateDate).containsExactly(LocalDate.of(2026, 11, 16));
        assertThat(fetch.rejections()).hasSize(2);
        assertThat(fetch.rejections().get(1).reason()).isEqualTo("2026-11-13 more than 4 decimal places (20.95412)");
    }

    private FrankfurterClient client() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v2";
        FxCollectorProperties properties = KoreaeximClientTest.properties("http://127.0.0.1:1", "", baseUrl);
        return new FrankfurterClient(new FxRestClientConfig().frankfurterRestClient(RestClient.builder(), properties));
    }
}
