package com.hanstep.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.error.GlobalExceptionHandler;
import com.hanstep.common.security.InternalApiProperties;
import com.hanstep.common.web.RequestIdFilter;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** AI 호출이 실패하면 500 이 아니라 502 AI_UPSTREAM_ERROR. 로컬 HTTP 서버를 AI 대신 띄운다. */
class AiUpstreamErrorTest {

    private static final String TOKEN = "test-ai-internal-token-0123456789abcdef";
    private static final String AI_ERROR_BODY = "{\"code\":\"INTERNAL\",\"message\":\"ai-side-detail\"}";

    private volatile int aiStatus;
    private volatile long aiDelayMillis;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            sleep(aiDelayMillis);
            byte[] body = AI_ERROR_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(aiStatus, body.length);
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

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 500, 503})
    void AI가_오류로_응답하면_502(int status) throws Exception {
        aiStatus = status;

        MvcResult result = expectBadGateway(mockMvc(serverUrl(), Duration.ofSeconds(5)));

        // AI 응답 본문은 그대로 내보내지 않는다
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ai-side-detail");
    }

    @Test
    void AI에_연결할_수_없으면_502() throws Exception {
        expectBadGateway(mockMvc("http://127.0.0.1:" + closedPort(), Duration.ofSeconds(5)));
    }

    @Test
    void AI_응답이_타임아웃보다_늦으면_502() throws Exception {
        aiStatus = 200;
        aiDelayMillis = 1_000;

        expectBadGateway(mockMvc(serverUrl(), Duration.ofMillis(200)));
    }

    private static MvcResult expectBadGateway(MockMvc mockMvc) throws Exception {
        return mockMvc.perform(post("/test/ai-call"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"))
                .andExpect(jsonPath("$.message").value("ai server error"))
                .andExpect(jsonPath("$.requestId").exists())
                .andReturn();
    }

    private static MockMvc mockMvc(String aiBaseUrl, Duration readTimeout) {
        AiApiProperties ai = new AiApiProperties(aiBaseUrl, TOKEN, Duration.ofSeconds(1), readTimeout, Duration.ofSeconds(90));
        RestClient aiRestClient = new AiRestClientConfig()
                .aiRestClient(RestClient.builder(), ai, new InternalApiProperties(null));
        return MockMvcBuilders.standaloneSetup(new CallAiController(aiRestClient))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // 잠깐 열었다 닫은 포트. 연결하면 거부된다
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // AI 를 호출하는 기능 대신 쓰는 테스트용 컨트롤러
    @RestController
    static class CallAiController {

        private final RestClient aiRestClient;

        CallAiController(RestClient aiRestClient) {
            this.aiRestClient = aiRestClient;
        }

        @PostMapping("/test/ai-call")
        void call() {
            aiRestClient.post().uri("/internal/v1/finance/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity();
        }
    }
}
