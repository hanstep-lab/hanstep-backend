package com.hanstep.visa.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanstep.common.ai.AiApiProperties;
import com.hanstep.common.ai.AiRestClientConfig;
import com.hanstep.common.security.InternalApiProperties;
import com.hanstep.visa.client.AiE74Client.AiCallException;
import com.hanstep.visa.client.AiE74Client.AiValidationException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AiE74Client 의 응답·오류 변환. 로컬 HTTP 서버를 AI 대신 띄운다. */
class AiE74ClientTest {

    private static final String TOKEN = "test-ai-internal-token-0123456789abcdef";
    private static final String VALID = "{\"requestKey\": \"k1\", \"status\": \"ELIGIBLE\", \"score\": {}, \"items\": []}";

    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private volatile int responseStatus;
    private volatile String responseBody;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void 정상_응답은_JsonNode_그대로_돌려준다() {
        respond(200, VALID);

        JsonNode response = client().calculate(JsonMapper.builder().build().readTree("{\"asOfDate\": \"2026-11-12\"}"));

        assertThat(response.path("requestKey").stringValue()).isEqualTo("k1");
        assertThat(receivedPath.get()).isEqualTo(AiE74Client.PATH);
        assertThat(receivedBody.get()).contains("\"asOfDate\":\"2026-11-12\"");
    }

    @Test
    void 응답이_400이면_AI가_준_필드_경로로_AiValidationException() {
        respond(400, """
                {"code": "VALIDATION_ERROR", "message": "invalid request",
                 "details": {"fields": [{"field": "scenario", "reason": "empty"}]}, "requestId": "req_1"}""");

        assertThatThrownBy(() -> client().calculate(emptyRequest()))
                .isInstanceOfSatisfying(AiValidationException.class, e ->
                        assertThat(e.getFields()).containsExactly(Map.of("field", "scenario", "reason", "empty")));
    }

    @Test
    void 그_밖의_오류_응답은_AI_HTTP_상태코드() {
        respond(503, "{\"code\": \"SERVICE_UNAVAILABLE\"}");

        assertThatThrownBy(() -> client().calculate(emptyRequest()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_HTTP_503"));
    }

    @Test
    void 필수_필드가_없거나_JSON이_아니면_AI_BAD_RESPONSE() {
        respond(200, "{\"status\": \"ELIGIBLE\"}");
        assertThatThrownBy(() -> client().calculate(emptyRequest()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));

        respond(200, "not json");
        assertThatThrownBy(() -> client().calculate(emptyRequest()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));
    }

    @Test
    void 연결할_수_없으면_AI_UNREACHABLE() {
        AiE74Client client = client();
        server.stop(0);

        assertThatThrownBy(() -> client.calculate(emptyRequest()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_UNREACHABLE"));
    }

    private void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
    }

    private static JsonNode emptyRequest() {
        return JsonMapper.builder().build().createObjectNode();
    }

    private AiE74Client client() {
        AiApiProperties ai = new AiApiProperties("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(90));
        RestClient restClient = new AiRestClientConfig().aiRestClient(RestClient.builder(), ai,
                new InternalApiProperties("test-internal-api-key-0123456789abcdef"));
        return new AiE74Client(restClient);
    }
}
