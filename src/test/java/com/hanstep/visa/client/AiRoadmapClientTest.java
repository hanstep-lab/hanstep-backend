package com.hanstep.visa.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanstep.common.ai.AiApiProperties;
import com.hanstep.common.ai.AiRestClientConfig;
import com.hanstep.common.security.InternalApiProperties;
import com.hanstep.visa.client.AiRoadmapClient.AiCallException;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AiRoadmapClient 의 응답·오류 변환. 로컬 HTTP 서버를 AI 대신 띄운다. */
class AiRoadmapClientTest {

    private static final String TOKEN = "test-ai-internal-token-0123456789abcdef";
    private static final String VALID = "{\"assessmentRequestKey\": \"k1\", \"stay\": {}, \"steps\": []}";

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

        JsonNode response = client().create(JsonMapper.builder().build().readTree("{\"stayExpiresOn\": \"2027-01-06\"}"));

        assertThat(response.path("assessmentRequestKey").stringValue()).isEqualTo("k1");
        assertThat(receivedPath.get()).isEqualTo(AiRoadmapClient.PATH);
        assertThat(receivedBody.get()).contains("\"stayExpiresOn\":\"2027-01-06\"");
    }

    @Test
    void 오류_응답은_400도_AI_HTTP_상태코드() {
        // 보내는 profile 은 바로 앞 E-7-4 계산이 받아 준 값이라 400 도 AI 쪽 문제로 본다
        respond(400, "{\"code\": \"VALIDATION_ERROR\", \"details\": {\"fields\": [{\"field\": \"profile\"}]}}");
        assertThatThrownBy(() -> client().create(emptyRequest()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_HTTP_400"));

        respond(503, "{\"code\": \"SERVICE_UNAVAILABLE\"}");
        assertThatThrownBy(() -> client().create(emptyRequest()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_HTTP_503"));
    }

    @Test
    void 필수_필드가_없거나_JSON이_아니면_AI_BAD_RESPONSE() {
        respond(200, "{\"assessmentRequestKey\": \"k1\", \"steps\": []}");
        assertThatThrownBy(() -> client().create(emptyRequest()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));

        respond(200, "not json");
        assertThatThrownBy(() -> client().create(emptyRequest()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));
    }

    @Test
    void 연결할_수_없으면_AI_UNREACHABLE() {
        AiRoadmapClient client = client();
        server.stop(0);

        assertThatThrownBy(() -> client.create(emptyRequest()))
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

    private AiRoadmapClient client() {
        AiApiProperties ai = new AiApiProperties("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(90));
        RestClient restClient = new AiRestClientConfig().aiRestClient(RestClient.builder(), ai,
                new InternalApiProperties("test-internal-api-key-0123456789abcdef"));
        return new AiRoadmapClient(restClient);
    }
}
