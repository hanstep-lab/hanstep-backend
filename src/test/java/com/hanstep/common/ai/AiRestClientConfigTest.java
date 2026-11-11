package com.hanstep.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanstep.common.security.InternalApiProperties;
import com.hanstep.common.web.RequestIdFilter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** aiRestClient 가 실제 HTTP 로 토큰·요청 ID 를 붙여 보내는지. 로컬 HTTP 서버를 AI 대신 띄운다. */
class AiRestClientConfigTest {

    private static final String TOKEN = "test-ai-internal-token-0123456789abcdef"; // 32자 이상
    private static final String SPRING_KEY = "test-internal-api-key-0123456789abcdef";
    private static final String PATH = "/internal/v1/finance/analyze";

    private final AtomicReference<Headers> received = new AtomicReference<>();
    private final AtomicReference<String> receivedProtocol = new AtomicReference<>();
    private volatile long responseDelayMillis;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.set(exchange.getRequestHeaders());
            receivedProtocol.set(exchange.getProtocol());
            sleep(responseDelayMillis);
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void 토큰과_현재_요청_ID를_붙여_HTTP_1_1로_보낸다() {
        MDC.put(RequestIdFilter.MDC_KEY, "req_abc123");

        post(client(TOKEN, Duration.ofSeconds(5)));

        assertThat(received.get().getFirst("X-Internal-Token")).isEqualTo(TOKEN);
        assertThat(received.get().getFirst("X-Request-Id")).isEqualTo("req_abc123");
        assertThat(receivedProtocol.get()).isEqualTo("HTTP/1.1");
        assertThat(received.get().containsKey("Upgrade")).isFalse();
    }

    @Test
    void 처리_중인_요청이_없으면_요청_ID를_보내지_않는다() {
        post(client(TOKEN, Duration.ofSeconds(5)));

        assertThat(received.get().containsKey("X-Request-Id")).isFalse();
    }

    @Test
    void 토큰이_비어_있으면_빈_토큰_헤더를_보내지_않는다() {
        post(client("", Duration.ofSeconds(5)));

        assertThat(received.get().containsKey("X-Internal-Token")).isFalse();
    }

    @Test
    void 응답이_타임아웃보다_늦으면_실패한다() {
        responseDelayMillis = 1_000;

        assertThatThrownBy(() -> post(client(TOKEN, Duration.ofMillis(200))))
                .isInstanceOf(ResourceAccessException.class);
    }

    private RestClient client(String token, Duration readTimeout) {
        AiApiProperties ai = new AiApiProperties("http://127.0.0.1:" + server.getAddress().getPort(), token,
                Duration.ofSeconds(1), readTimeout);
        return new AiRestClientConfig().aiRestClient(RestClient.builder(), ai, new InternalApiProperties(SPRING_KEY));
    }

    private static void post(RestClient client) {
        client.post().uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"transactions\":[]}")
                .retrieve()
                .toBodilessEntity();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
