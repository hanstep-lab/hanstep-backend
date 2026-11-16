package com.hanstep.credit.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanstep.common.ai.AiApiProperties;
import com.hanstep.common.ai.AiRestClientConfig;
import com.hanstep.common.security.InternalApiProperties;
import com.hanstep.credit.client.AiAltCreditClient.AiCallException;
import com.hanstep.credit.client.AiAltCreditClient.AiValidationException;
import com.hanstep.credit.client.AiAltCreditClient.AssessRequest;
import com.hanstep.credit.client.AiAltCreditClient.AssessResponse;
import com.hanstep.credit.domain.AltCreditDecision;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AiAltCreditClient 의 요청 형식과 응답·오류 변환. 로컬 HTTP 서버를 AI 대신 띄운다. */
class AiAltCreditClientTest {

    private static final String TOKEN = "test-ai-internal-token-0123456789abcdef";
    // AI app/credit/alt_score.py AltCreditAssessment 형식(camelCase). 모르는 필드(extra)는 무시해야 한다
    private static final String VALID = """
            {"modelVersion": "alt-credit-v9", "isMock": true, "pd": 0.0123, "score": 850,
             "rateBreakdown": {"base": 0.0383, "creditPremium": 0.0055, "capitalCost": 0.0061,
                               "otherSpread": 0.073547, "spread": 0.0851, "preferential": 0.01, "final": 0.1134},
             "rateBaseDate": "2026-11-11", "loanTermMonths": 9, "minLimit": 5000000, "maxLimit": 7000000,
             "maxLimitBinding": "SIMPLE_DSR_40", "decision": "ELIGIBLE", "reasonCode": null, "inputWarnings": [],
             "disclaimer": "모의 사전진단이며 실제 대출 승인이나 신용점수가 아닙니다", "extra": 1}""";

    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile long delayMillis;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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
    void 요청은_AI_필드_이름으로_보내고_null도_보낸다() {
        respond(200, VALID);

        client().assess(request(null, null));

        assertThat(receivedPath.get()).isEqualTo(AiAltCreditClient.PATH);
        JsonNode body = JsonMapper.builder().build().readTree(receivedBody.get());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("isMock", "stayDurationMonths", "monthsToExpiry",
                "monthlyIncome", "workPermitted", "salaryMonths", "shinhanPayroll", "hasSubscription", "isRefinance",
                "telLate6m", "utilLate6m", "remitRatio", "remitMonths6m");
        assertThat(body.path("isMock").booleanValue()).isTrue();
        assertThat(body.path("telLate6m").intValue()).isEqualTo(0);
        assertThat(body.path("remitRatio").decimalValue()).isEqualByComparingTo("0.2979");
        // AI 는 두 필드에 기본값이 없어 null 을 명시해야 한다(만료 없음·송금 안 함)
        assertThat(body.path("monthsToExpiry").isNull()).isTrue();
        assertThat(body.path("remitMonths6m").isNull()).isTrue();
    }

    @Test
    void 정상_응답을_필드별로_읽는다() {
        respond(200, VALID);

        AssessResponse response = client().assess(request(10, 6));

        assertThat(response.modelVersion()).isEqualTo("alt-credit-v9");
        assertThat(response.isMock()).isTrue();
        assertThat(response.score()).isEqualTo(850);
        assertThat(response.rateBreakdown().finalRate()).isEqualTo(0.1134);
        assertThat(response.rateBaseDate()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(response.maxLimit()).isEqualTo(7_000_000L);
        assertThat(response.decision()).isEqualTo(AltCreditDecision.ELIGIBLE);
        assertThat(response.reasonCode()).isNull();
        assertThat(response.inputWarnings()).isEmpty();
    }

    @Test
    void 응답이_400이면_AI가_준_필드_경로로_AiValidationException() {
        respond(400, """
                {"code": "VALIDATION_ERROR",
                 "message": "stayDurationMonths must be between 6 and 120 (model training range)",
                 "details": {"fields": [{"field": "stayDurationMonths", "reason": "out_of_training_range"}]},
                 "requestId": "req_1"}""");

        assertThatThrownBy(() -> client().assess(request(10, 6)))
                .isInstanceOfSatisfying(AiValidationException.class, e -> assertThat(e.getFields())
                        .containsExactly(Map.of("field", "stayDurationMonths", "reason", "out_of_training_range")));
    }

    @Test
    void 그_밖의_오류_응답은_AI_HTTP_상태코드() {
        respond(503, "{\"code\": \"SERVICE_UNAVAILABLE\"}");

        assertThatThrownBy(() -> client().assess(request(10, 6)))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_HTTP_503"));
    }

    @Test
    void 판정이_없거나_모르는_값이거나_JSON이_아니면_AI_BAD_RESPONSE() {
        respond(200, VALID.replace("\"decision\": \"ELIGIBLE\",", ""));
        assertThatThrownBy(() -> client().assess(request(10, 6)))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));

        respond(200, VALID.replace("\"decision\": \"ELIGIBLE\"", "\"decision\": \"APPROVED\""));
        assertThatThrownBy(() -> client().assess(request(10, 6)))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));

        respond(200, "not json");
        assertThatThrownBy(() -> client().assess(request(10, 6)))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));
    }

    @Test
    void 응답이_read_timeout보다_늦으면_AI_UNREACHABLE() {
        respond(200, VALID);
        delayMillis = 2_500;

        assertThatThrownBy(() -> client().assess(request(10, 6)))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_UNREACHABLE"));
    }

    @Test
    void 연결할_수_없으면_AI_UNREACHABLE() {
        AiAltCreditClient client = client();
        server.stop(0);

        assertThatThrownBy(() -> client.assess(request(10, 6)))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_UNREACHABLE"));
    }

    private void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
    }

    private static AssessRequest request(Integer monthsToExpiry, Integer remitMonths6m) {
        return new AssessRequest(true, 47, monthsToExpiry, 2_350_000, true, 12, true, false, false, 0, 0,
                new BigDecimal("0.2979"), remitMonths6m);
    }

    private AiAltCreditClient client() {
        AiApiProperties ai = new AiApiProperties("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(90));
        RestClient restClient = new AiRestClientConfig().aiRestClient(RestClient.builder(), ai,
                new InternalApiProperties("test-internal-api-key-0123456789abcdef"));
        return new AiAltCreditClient(restClient);
    }
}
