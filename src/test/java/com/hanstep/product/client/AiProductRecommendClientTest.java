package com.hanstep.product.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanstep.common.ai.AiApiProperties;
import com.hanstep.common.ai.AiRestClientConfig;
import com.hanstep.common.security.InternalApiProperties;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.product.client.AiProductRecommendClient.AiCallException;
import com.hanstep.product.client.AiProductRecommendClient.Conditions;
import com.hanstep.product.client.AiProductRecommendClient.Profile;
import com.hanstep.product.client.AiProductRecommendClient.RecommendRequest;
import com.hanstep.product.client.AiProductRecommendClient.RecommendResponse;
import com.hanstep.product.dto.ProductRecommendResponse.AmountRange;
import com.hanstep.product.dto.ProductRecommendResponse.Product;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AiProductRecommendClient 의 요청 형식과 응답·오류 변환. 로컬 HTTP 서버를 AI 대신 띄운다. */
class AiProductRecommendClientTest {

    private static final String TOKEN = "test-ai-internal-token-0123456789abcdef";
    // AI 계약(ai/specs/products/recommend_api_v1.md) 2절 형식. 모르는 필드(extra)는 무시해야 한다
    static final String VALID = """
            {"asOf": "2026-11-17", "remainingStayMonths": 10, "rankedBy": "catalog_no_query", "abstainReason": null,
             "products": [
               {"productId": "doc-a32e7bfdf57e", "nameKo": "슈퍼SOL 웰컴 정기예금", "kind": "DEPOSIT",
                "termMonths": [6, 12], "monthlyAmountKrw": null,
                "depositAmountKrw": {"min": 500000, "max": 20000000}, "accrual": null,
                "foreignerEligible": "inferred", "notChecked": ["visa"], "stayFits": true,
                "baseRate": {"ratePct": 3.3, "asOf": "2026-11-09", "quote": "기본이자율 6개월 : 연 3.3%", "page": 2},
                "maxRate": null, "feature": {"quote": "정기예금", "page": 1},
                "evidence": [{"about": "term", "quote": "계약기간 6개월, 12개월", "page": 1}]},
               {"productId": "doc-b89237ab049e", "nameKo": "신한 스마트 적금", "kind": "SAVINGS",
                "termMonths": {"min": 12, "max": 12}, "monthlyAmountKrw": {"min": 1000, "max": "no limit"},
                "depositAmountKrw": null, "accrual": "FLEXIBLE", "foreignerEligible": null,
                "notChecked": ["spareMoney"], "stayFits": null,
                "baseRate": null, "maxRate": {"ratePct": 3.25, "quote": "최종이자율 연 3.25%", "page": 2},
                "feature": null, "evidence": []}],
             "excludedSummary": [{"reason": "stayTerm", "count": 5}], "extra": 1}""";

    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile long delayMillis;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedToken.set(exchange.getRequestHeaders().getFirst(InternalTokenFilter.HEADER));
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
    void 요청은_계약_이름으로_보내고_프로필_null은_보내며_조건은_넘긴_것만_보낸다() {
        respond(200, VALID);

        client().recommend(new RecommendRequest(LocalDate.of(2026, 11, 17), new Profile("E-9", null, null), null,
                new Conditions("SAVINGS", 12, null, null, null), null));

        assertThat(receivedPath.get()).isEqualTo(AiProductRecommendClient.PATH);
        assertThat(receivedToken.get()).isEqualTo(TOKEN);
        JsonNode body = JsonMapper.builder().build().readTree(receivedBody.get());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("asOf", "profile", "monthlySpareKrw", "conditions",
                "queryKo");
        assertThat(body.path("asOf").stringValue()).isEqualTo("2026-11-17");
        assertThat(body.path("profile").propertyNames()).containsExactlyInAnyOrder("visaTypeCode", "age",
                "stayExpiresOn");
        assertThat(body.path("profile").path("visaTypeCode").stringValue()).isEqualTo("E-9");
        assertThat(body.path("profile").path("age").isNull()).isTrue();
        assertThat(body.path("monthlySpareKrw").isNull()).isTrue();
        assertThat(body.path("queryKo").isNull()).isTrue();
        // AI 는 requireForeignerExplicit 에 null 을 받지 않는다
        assertThat(body.path("conditions").propertyNames()).containsExactlyInAnyOrder("productType", "maxTermMonths");
        assertThat(body.path("conditions").path("maxTermMonths").intValue()).isEqualTo(12);
    }

    @Test
    void 정상_응답을_값_그대로_읽는다() {
        respond(200, VALID);

        RecommendResponse response = client().recommend(request());

        assertThat(response.asOf()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(response.remainingStayMonths()).isEqualTo(10);
        assertThat(response.rankedBy()).isEqualTo("catalog_no_query");
        assertThat(response.abstainReason()).isNull();
        assertThat(response.excludedSummary()).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("stayTerm"))
                .satisfies(e -> assertThat(e.count()).isEqualTo(5));

        Product deposit = response.products().get(0);
        assertThat(deposit.termMonths()).isEqualTo(List.of(6, 12));
        assertThat(deposit.monthlyAmountKrw()).isNull();
        assertThat(deposit.depositAmountKrw()).isEqualTo(new AmountRange(500_000L, 20_000_000));
        assertThat(deposit.baseRate().ratePct()).isEqualByComparingTo("3.3");
        assertThat(deposit.baseRate().asOf()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(deposit.maxRate()).isNull();
        assertThat(deposit.stayFits()).isTrue();

        Product savings = response.products().get(1);
        assertThat(savings.termMonths()).isEqualTo(Map.of("min", 12, "max", 12));
        assertThat(savings.monthlyAmountKrw()).isEqualTo(new AmountRange(1_000L, "no limit"));
        assertThat(savings.baseRate()).isNull();
        assertThat(savings.maxRate().ratePct()).isEqualTo(new BigDecimal("3.25"));
        assertThat(savings.feature()).isNull();
        assertThat(savings.foreignerEligible()).isNull();
        assertThat(savings.stayFits()).isNull();
        assertThat(savings.evidence()).isEmpty();
    }

    @Test
    void 기권_응답도_그대로_읽는다() {
        respond(200, """
                {"asOf": "2026-11-17", "remainingStayMonths": 10, "rankedBy": "catalog_no_query",
                 "abstainReason": "STAY_TOO_SHORT", "products": [],
                 "excludedSummary": [{"reason": "stayTerm", "count": 13}]}""");

        RecommendResponse response = client().recommend(request());

        assertThat(response.abstainReason()).isEqualTo("STAY_TOO_SHORT");
        assertThat(response.products()).isEmpty();
    }

    @Test
    void 오류_응답은_AI_HTTP_상태코드() {
        respond(401, "{\"code\": \"UNAUTHORIZED\"}");
        assertThatThrownBy(() -> client().recommend(request()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_HTTP_401"));

        respond(500, "{\"code\": \"INTERNAL_ERROR\"}");
        assertThatThrownBy(() -> client().recommend(request()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_HTTP_500"));
    }

    @Test
    void 필수_필드가_없거나_JSON이_아니면_AI_BAD_RESPONSE() {
        respond(200, VALID.replace("\"rankedBy\": \"catalog_no_query\",", ""));
        assertThatThrownBy(() -> client().recommend(request()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));

        respond(200, VALID.replace("\"productId\": \"doc-a32e7bfdf57e\",", ""));
        assertThatThrownBy(() -> client().recommend(request()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));

        respond(200, "not json");
        assertThatThrownBy(() -> client().recommend(request()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));
    }

    @Test
    void 응답이_read_timeout보다_늦으면_AI_UNREACHABLE() {
        respond(200, VALID);
        delayMillis = 2_500;

        assertThatThrownBy(() -> client().recommend(request()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_UNREACHABLE"));
    }

    @Test
    void 연결할_수_없으면_AI_UNREACHABLE() {
        AiProductRecommendClient client = client();
        server.stop(0);

        assertThatThrownBy(() -> client.recommend(request()))
                .isInstanceOfSatisfying(AiCallException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("AI_UNREACHABLE"));
    }

    private void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
    }

    private static RecommendRequest request() {
        return new RecommendRequest(LocalDate.of(2026, 11, 17), new Profile("E-9", 30, LocalDate.of(2027, 10, 4)),
                675_317L, new Conditions(null, null, null, null, null), null);
    }

    private AiProductRecommendClient client() {
        AiApiProperties ai = new AiApiProperties("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(90));
        RestClient restClient = new AiRestClientConfig().aiRestClient(RestClient.builder(), ai,
                new InternalApiProperties("test-internal-api-key-0123456789abcdef"));
        return new AiProductRecommendClient(restClient);
    }
}
