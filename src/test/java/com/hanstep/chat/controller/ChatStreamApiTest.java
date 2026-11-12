package com.hanstep.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.hanstep.chat.repository.ChatRepository;
import com.hanstep.chat.support.FakeAgentServer;
import com.hanstep.chat.support.SseTestClient;
import com.hanstep.chat.support.SseTestClient.Result;
import com.hanstep.common.security.JwtTokenProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CHT-04. 실제 포트로 띄운 Spring 에 앱 역할 HTTP 클라이언트로 요청하고, AI(INT-05) 자리에는 가짜 SSE 서버를 둔다.
 * 실제 AI 서버·외부 API 는 부르지 않는다. 연결 끊김·비동기 디스패치를 보려고 MockMvc 대신 실제 HTTP 를 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ChatStreamApiTest {

    private static final String USER_PREFIX = "test-chat-stream";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final FakeAgentServer AI = start();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    // 실제 동작 그대로, 근거 저장 실패 테스트에서만 예외를 낸다
    @MockitoSpyBean
    private ChatRepository chatRepository;

    private SseTestClient client;
    private long userId;
    private String bearer;
    private long sessionId;

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("hanstep.ai.base-url", AI::baseUrl);
    }

    @AfterAll
    static void stopAi() {
        AI.close();
    }

    @BeforeEach
    void setUp() {
        cleanUp();
        AI.reset();
        client = new SseTestClient(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                "http://127.0.0.1:" + port);
        userId = createUser(USER_PREFIX + "-a", "vi");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        sessionId = session(userId, "ACTIVE");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE external_user_key LIKE ?", USER_PREFIX + "%");
    }

    @Test
    void 정상_흐름은_이벤트_순서와_저장(CapturedOutput output) throws Exception {
        AI.script((request, out) -> {
            out.event("agent.tool_call", "{\"order\":1,\"toolName\":\"get_financial_analysis\",\"arguments\":{\"period\":\"latest\"},"
                    + "\"status\":\"SUCCEEDED\",\"resultRef\":{\"type\":\"FINANCIAL_ANALYSIS\",\"id\":7},\"latencyMs\":35,"
                    + "\"errorCode\":null}");
            out.event("agent.delta", "{\"text\":\"You can \"}");
            out.event("agent.delta", "{\"text\":\"save 675,317 KRW.\"}");
            out.event("agent.sources", "{\"sources\":[{\"type\":\"FINANCIAL_ANALYSIS\",\"id\":7,\"chunkRef\":null,"
                    + "\"pageLabel\":null},{\"type\":\"WEB_PAGE\",\"id\":1}]}");
            out.event("agent.done", "{\"content\":\"You can save 675,317 KRW.\",\"blocks\":[{\"type\":\"BALANCE_CARD\"}],"
                    + "\"modelId\":\"fake-model\",\"promptVersion\":\"chat-v1\",\"latencyMs\":120,"
                    + "\"usage\":{\"inputTokens\":100,\"outputTokens\":20,\"cacheReadInputTokens\":0,"
                    + "\"cacheCreationInputTokens\":0,\"llmCalls\":2},"
                    + "\"timings\":{\"firstDeltaMs\":40,\"toolMs\":35,\"totalMs\":120}}");
        });

        Result result = client.stream(bearer, sessionId, SseTestClient.body("c-1", "How much can I save?"), null);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.contentType()).startsWith("text/event-stream");
        assertThat(result.names()).containsExactly("chat.started", "chat.tool_call", "chat.delta", "chat.delta",
                "chat.sources", "chat.done");
        JsonNode started = json(result.first("chat.started"));
        String runId = started.path("runId").asString();
        long userMessageId = started.path("userMessageId").asLong();
        long assistantMessageId = started.path("assistantMessageId").asLong();
        assertThat(runId).matches("run_[0-9a-f]{32}");
        for (SseTestClient.Event event : result.events()) {
            assertThat(json(event).path("runId").asString()).isEqualTo(runId);
            assertThat(event.data()).doesNotContain("\n");
        }
        JsonNode tool = json(result.first("chat.tool_call"));
        assertThat(tool.path("order").asInt()).isEqualTo(1);
        assertThat(tool.path("toolName").asString()).isEqualTo("get_financial_analysis");
        assertThat(tool.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(tool.path("resultRef").path("type").asString()).isEqualTo("FINANCIAL_ANALYSIS");
        assertThat(tool.path("resultRef").path("id").asLong()).isEqualTo(7);
        assertThat(tool.path("latencyMs").asInt()).isEqualTo(35);
        assertThat(tool.has("arguments")).isFalse();
        assertThat(json(result.events().get(2)).path("text").asString()).isEqualTo("You can ");
        JsonNode sources = json(result.first("chat.sources")).path("sources");
        assertThat(sources.size()).isEqualTo(1); // WEB_PAGE 는 스키마에 없어 뺌
        assertThat(sources.get(0).path("type").asString()).isEqualTo("FINANCIAL_ANALYSIS");
        JsonNode done = json(result.first("chat.done"));
        assertThat(done.path("assistantMessageId").asLong()).isEqualTo(assistantMessageId);
        assertThat(done.path("blocks").get(0).path("type").asString()).isEqualTo("BALANCE_CARD");
        assertThat(done.path("latencyMs").asInt()).isPositive();

        Map<String, Object> user = row(userMessageId);
        assertThat(user.get("role")).isEqualTo("USER");
        assertThat(user.get("status")).isEqualTo("COMPLETED");
        assertThat(user.get("client_message_id")).isEqualTo("c-1");
        assertThat(user.get("run_id")).isEqualTo(runId);
        assertThat(user.get("content")).isEqualTo("How much can I save?");
        Map<String, Object> assistant = row(assistantMessageId);
        assertThat(assistant.get("role")).isEqualTo("ASSISTANT");
        assertThat(assistant.get("status")).isEqualTo("COMPLETED");
        assertThat(assistant.get("content")).isEqualTo("You can save 675,317 KRW.");
        assertThat(assistant.get("model_id")).isEqualTo("fake-model");
        assertThat(assistant.get("prompt_version")).isEqualTo("chat-v1");
        assertThat(((Number) assistant.get("latency_ms")).intValue()).isEqualTo(done.path("latencyMs").asInt());
        assertThat(assistant.get("reply_to_id")).hasToString(String.valueOf(userMessageId));
        assertThat(assistant.get("run_id")).isEqualTo(runId);
        assertThat(assistant.get("blocks_json").toString()).contains("BALANCE_CARD");
        assertThat(assistant.get("error_code")).isNull();

        Map<String, Object> toolRow = jdbc.queryForMap("SELECT * FROM chat_tool_calls WHERE message_id = ?",
                assistantMessageId);
        assertThat(toolRow.get("run_id")).isEqualTo(runId);
        assertThat(toolRow.get("call_order")).hasToString("1");
        assertThat(toolRow.get("tool_name")).isEqualTo("get_financial_analysis");
        assertThat(toolRow.get("arguments_json").toString()).contains("latest");
        assertThat(toolRow.get("status")).isEqualTo("SUCCEEDED");
        assertThat(toolRow.get("result_ref_type")).isEqualTo("FINANCIAL_ANALYSIS");
        assertThat(toolRow.get("result_ref_id")).hasToString("7");
        assertThat(toolRow.get("latency_ms")).hasToString("35");
        assertThat(jdbc.queryForList("SELECT source_type FROM chat_message_sources WHERE message_id = ?",
                String.class, assistantMessageId)).containsExactly("FINANCIAL_ANALYSIS");
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM chat_sessions WHERE id = ?",
                LocalDateTime.class, sessionId)).isNotNull();

        // 측정용 기록: 답변마다 runId 기준 한 줄(원문·금액 없이)
        String line = waitForLine(output, "chat run finished runId=" + runId);
        assertThat(line).contains("status=COMPLETED", "aiFirstDeltaMs=40", "aiTotalMs=120", "inputTokens=100",
                "llmCalls=2").containsPattern("springFirstDeltaMs=\\d+").containsPattern("relayFirstDeltaMs=-?\\d+");
        assertThat(line).doesNotContain("675,317").doesNotContain("save");
    }

    @Test
    void AI로_보낸_요청은_최근_완성_3쌍과_언어_JWT_사용자_마스킹() throws Exception {
        LocalDateTime base = LocalDateTime.parse("2026-11-01T00:00:00");
        // 완성된 쌍 m1/m2 ~ m7/m8 사이와 끝에 답변이 실패·끊긴 질문을 섞는다
        for (int i = 1; i <= 7; i += 2) {
            long question = message(sessionId, "USER", "m" + i, "COMPLETED", base.plusMinutes(i * 10), null);
            message(sessionId, "ASSISTANT", "m" + (i + 1), "COMPLETED", base.plusMinutes(i * 10 + 1), question);
            if (i == 5) {
                long failedQuestion = message(sessionId, "USER", "q-failed", "COMPLETED", base.plusMinutes(i * 10 + 2),
                        null);
                message(sessionId, "ASSISTANT", "failed", "FAILED", base.plusMinutes(i * 10 + 3), failedQuestion);
            }
        }
        long partialQuestion = message(sessionId, "USER", "q-partial", "COMPLETED", base.plusMinutes(90), null);
        message(sessionId, "ASSISTANT", "partial", "PARTIAL", base.plusMinutes(91), partialQuestion);
        AI.script((request, out) -> out.event("agent.done", "{\"content\":\"ok\"}"));

        Result result = client.stream(bearer, sessionId,
                SseTestClient.body("c-hist", "my ARC 900101-5234567 card 1234567812345678"), null);
        assertThat(result.names()).last().isEqualTo("chat.done");

        assertThat(AI.received()).hasSize(1);
        FakeAgentServer.Received received = AI.received().get(0);
        assertThat(received.path()).isEqualTo("/internal/v1/agent/chat/stream");
        JsonNode sent = MAPPER.readTree(received.body());
        assertThat(sent.path("runId").asString()).isEqualTo(json(result.first("chat.started")).path("runId").asString());
        assertThat(sent.path("userId").asLong()).isEqualTo(userId);
        assertThat(sent.path("sessionId").asLong()).isEqualTo(sessionId);
        assertThat(sent.path("language").asString()).isEqualTo("vi");
        assertThat(sent.path("message").asString()).isEqualTo("my ARC ******-******* card ****************");
        assertThat(sent.path("history").valueStream().map(h -> h.path("content").asString()).toList())
                .containsExactly("m3", "m4", "m5", "m6", "m7", "m8");
        // USER 로 시작하고 USER·ASSISTANT 가 번갈아 온다
        assertThat(sent.path("history").valueStream().map(h -> h.path("role").asString()).toList())
                .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT", "USER", "ASSISTANT");
        assertThat(sent.path("history").get(0).size()).isEqualTo(2); // role·content 만
        assertThat(received.requestId()).isNotBlank();

        long userMessageId = json(result.first("chat.started")).path("userMessageId").asLong();
        assertThat(row(userMessageId).get("content")).isEqualTo("my ARC ******-******* card ****************");
    }

    @Test
    void 같은_clientMessageId는_409이고_AI를_부르지_않는다() throws Exception {
        AI.script((request, out) -> out.event("agent.done", "{\"content\":\"first\"}"));
        Result first = client.stream(bearer, sessionId, SseTestClient.body("dup-1", "hello"), null);
        long userMessageId = json(first.first("chat.started")).path("userMessageId").asLong();
        assertThat(AI.received()).hasSize(1);
        AI.reset();

        Result second = client.stream(bearer, sessionId, SseTestClient.body("dup-1", "hello again"), null);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.contentType()).startsWith("application/json");
        JsonNode error = MAPPER.readTree(second.body());
        assertThat(error.path("code").asString()).isEqualTo("DUPLICATE_REQUEST");
        assertThat(error.path("details").path("userMessageId").asLong()).isEqualTo(userMessageId);
        System.out.printf("[chat-measure] 409 duplicate: fake AI requests=%d%n", AI.received().size());
        assertThat(AI.received()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE session_id = ?", Long.class,
                sessionId)).isEqualTo(2);
    }

    @Test
    void 스트림_전_오류는_JSON_400_404() throws Exception {
        assertError(client.stream(bearer, sessionId, SseTestClient.body("c-1", "   "), null), 400, "VALIDATION_ERROR");
        assertError(client.stream(bearer, sessionId, SseTestClient.body("c-1", "a".repeat(2001)), null), 400,
                "VALIDATION_ERROR");
        assertError(client.stream(bearer, sessionId, SseTestClient.body("x".repeat(65), "hi"), null), 400,
                "VALIDATION_ERROR");
        assertError(client.stream(bearer, sessionId, SseTestClient.body("", "hi"), null), 400, "VALIDATION_ERROR");
        assertError(client.stream(bearer, session(createUser(USER_PREFIX + "-b", "en"), "ACTIVE"),
                SseTestClient.body("c-1", "hi"), null), 404, "RESOURCE_NOT_FOUND");
        assertError(client.stream(bearer, session(userId, "ARCHIVED"), SseTestClient.body("c-1", "hi"), null), 404,
                "RESOURCE_NOT_FOUND");
        assertError(client.stream(bearer, 999_999_999L, SseTestClient.body("c-1", "hi"), null), 404,
                "RESOURCE_NOT_FOUND");
        Result noToken = client.stream("Bearer x", sessionId, SseTestClient.body("c-1", "hi"), null);
        assertThat(noToken.status()).isEqualTo(401);
        assertThat(AI.received()).isEmpty();
    }

    @Test
    void agent_error는_FAILED이고_앱에는_AI_UPSTREAM_ERROR(CapturedOutput output) throws Exception {
        AI.script((request, out) -> {
            out.event("agent.delta", "{\"text\":\"Partial \"}");
            out.event("agent.error", "{\"code\":\"AGENT_TIMEOUT\",\"message\":\"internal detail\"}");
        });
        Result result = client.stream(bearer, sessionId, SseTestClient.body("c-err", "hi"), null);

        assertThat(result.names()).containsExactly("chat.started", "chat.delta", "chat.error");
        JsonNode error = json(result.first("chat.error"));
        // AI 코드는 앱에 내보내지 않고 error_code·로그에만 남긴다
        assertThat(error.path("code").asString()).isEqualTo("AI_UPSTREAM_ERROR");
        assertThat(error.path("message").asString()).doesNotContain("internal detail");
        Map<String, Object> assistant = row(json(result.first("chat.started")).path("assistantMessageId").asLong());
        assertThat(assistant.get("status")).isEqualTo("FAILED");
        assertThat(assistant.get("error_code")).isEqualTo("AGENT_TIMEOUT");
        assertThat(assistant.get("content")).isEqualTo("Partial ");
        assertThat(waitForLine(output, "chat run finished runId=" + error.path("runId").asString()))
                .contains("status=FAILED", "errorCode=AGENT_TIMEOUT");
    }

    @Test
    void 근거_저장이_실패해도_답변은_COMPLETED() throws Exception {
        doThrow(new DataIntegrityViolationException("test")).when(chatRepository).insertSources(anyLong(), anyList());
        AI.script((request, out) -> {
            out.event("agent.delta", "{\"text\":\"ok\"}");
            out.event("agent.sources", "{\"sources\":[{\"type\":\"FINANCIAL_ANALYSIS\",\"id\":7}]}");
            out.event("agent.done", "{\"content\":\"ok\"}");
        });
        Result result = client.stream(bearer, sessionId, SseTestClient.body("c-src", "hi"), null);

        assertThat(result.names()).containsExactly("chat.started", "chat.delta", "chat.sources", "chat.done");
        long assistantMessageId = json(result.first("chat.started")).path("assistantMessageId").asLong();
        assertThat(row(assistantMessageId).get("status")).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_sources WHERE message_id = ?", Long.class,
                assistantMessageId)).isZero();
    }

    @Test
    void done_없이_끝나거나_HTTP_오류면_FAILED_AI_UPSTREAM_ERROR() throws Exception {
        AI.script((request, out) -> out.event("agent.delta", "{\"text\":\"cut\"}"));
        assertUpstreamFailed(client.stream(bearer, sessionId, SseTestClient.body("c-eof", "hi"), null), "cut");

        AI.script((request, out) -> out.status(500));
        assertUpstreamFailed(client.stream(bearer, sessionId, SseTestClient.body("c-500", "hi"), null), "");
    }

    @Test
    void 이벤트가_한_조각에_여러_개_오거나_잘려_와도_파싱() throws Exception {
        AI.script((request, out) -> {
            out.raw(": ping\n\nevent: agent.delta\ndata: {\"text\":\"A\"}\n\nevent: agent.delta\ndata: {\"text\":\"B\"}\n\nevent: agent.del");
            Thread.sleep(100);
            out.raw("ta\ndata: {\"text\":\"C\"}\n\nevent: agent.unknown\ndata: {\"x\":1}\n\nevent: agent.delta\ndata: {\"text\":\n");
            Thread.sleep(100);
            out.raw("data: \"D\"}\n\nevent: agent.done\ndata: {\"modelId\":\"m\"}\n\n");
        });
        Result result = client.stream(bearer, sessionId, SseTestClient.body("c-chunk", "hi"), null);

        assertThat(result.names()).containsExactly("chat.started", "chat.delta", "chat.delta", "chat.delta",
                "chat.delta", "chat.done");
        assertThat(result.events().subList(1, 5).stream().map(e -> json(e).path("text").asString()).toList())
                .containsExactly("A", "B", "C", "D");
        // done 에 content 가 없으면 받은 조각을 이어 저장
        assertThat(row(json(result.first("chat.done")).path("assistantMessageId").asLong()).get("content"))
                .isEqualTo("ABCD");
    }

    @Test
    void 첫_delta는_스트림이_끝나기_전에_도착한다() throws Exception {
        AI.script((request, out) -> {
            for (int i = 0; i < 10; i++) {
                out.event("agent.delta", "{\"text\":\"t" + i + " \"}");
                Thread.sleep(200);
            }
            out.event("agent.done", "{}");
        });
        Result result = client.stream(bearer, sessionId, SseTestClient.body("c-buf", "hi"), null);

        long firstDelta = result.first("chat.delta").atMs();
        long done = result.first("chat.done").atMs();
        System.out.printf("[chat-measure] buffering: first chat.delta at %d ms, chat.done at %d ms%n", firstDelta, done);
        assertThat(result.names().stream().filter("chat.delta"::equals)).hasSize(10);
        assertThat(done - firstDelta).isGreaterThan(1_500); // 중간 버퍼링이 있으면 한꺼번에 온다
    }

    @Test
    void 앱이_끊으면_PARTIAL이고_AI_연결도_끊긴다() throws Exception {
        AtomicLong aiDetectedNanos = new AtomicLong();
        AI.script((request, out) -> {
            try {
                for (int i = 0; i < 100; i++) {
                    out.event("agent.delta", "{\"text\":\"p" + i + " \"}");
                    Thread.sleep(200);
                }
                out.event("agent.done", "{}");
            } catch (IOException e) {
                aiDetectedNanos.set(System.nanoTime());
            }
        });

        Result result = client.stream(bearer, sessionId, SseTestClient.body("c-gone", "hi"),
                events -> events.stream().filter(e -> e.name().equals("chat.delta")).count() == 2);
        long closedNanos = System.nanoTime();
        long assistantMessageId = json(result.first("chat.started")).path("assistantMessageId").asLong();

        String status = waitForStatus(assistantMessageId, 10_000);
        waitUntil(() -> aiDetectedNanos.get() != 0, 10_000);
        long detectMs = (aiDetectedNanos.get() - closedNanos) / 1_000_000;
        System.out.printf("[chat-measure] app disconnect -> fake AI detected close after %d ms (delta every 200 ms)%n",
                detectMs);

        assertThat(status).isEqualTo("PARTIAL");
        assertThat((String) row(assistantMessageId).get("content")).startsWith("p0 p1 ");
        assertThat(aiDetectedNanos.get()).isPositive();
        assertThat(detectMs).isLessThan(5_000);
    }

    private void assertUpstreamFailed(Result result, String content) {
        assertThat(result.names()).last().isEqualTo("chat.error");
        assertThat(json(result.first("chat.error")).path("code").asString()).isEqualTo("AI_UPSTREAM_ERROR");
        Map<String, Object> assistant = row(json(result.first("chat.started")).path("assistantMessageId").asLong());
        assertThat(assistant.get("status")).isEqualTo("FAILED");
        assertThat(assistant.get("error_code")).isEqualTo("AI_UPSTREAM_ERROR");
        assertThat(assistant.get("content")).isEqualTo(content);
    }

    private static void assertError(Result result, int status, String code) throws Exception {
        assertThat(result.status()).as(result.body()).isEqualTo(status);
        assertThat(result.contentType()).startsWith("application/json");
        assertThat(MAPPER.readTree(result.body()).path("code").asString()).isEqualTo(code);
    }

    private String waitForStatus(long messageId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String status;
        do {
            status = (String) row(messageId).get("status");
            if (!"STREAMING".equals(status)) {
                return status;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        return status;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private static String waitForLine(CapturedOutput output, String marker) throws InterruptedException {
        waitUntil(() -> output.getAll().contains(marker), 5_000);
        return output.getAll().lines().filter(l -> l.contains(marker)).findFirst().orElse("");
    }

    private Map<String, Object> row(long messageId) {
        return jdbc.queryForMap("SELECT * FROM chat_messages WHERE id = ?", messageId);
    }

    private long createUser(String key, String language) {
        jdbc.update("INSERT INTO users (external_user_key, display_name, preferred_language) VALUES (?, 'TEST', ?)",
                key, language);
        return jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class, key);
    }

    private long session(long owner, String status) {
        jdbc.update("INSERT INTO chat_sessions (user_id, status) VALUES (?, ?)", owner, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM chat_sessions WHERE user_id = ?", Long.class, owner);
    }

    private long message(long session, String role, String content, String status, LocalDateTime createdAt,
                         Long replyToId) {
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, status, reply_to_id, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", session, role, content, status, replyToId, createdAt);
        return jdbc.queryForObject("SELECT MAX(id) FROM chat_messages WHERE session_id = ?", Long.class, session);
    }

    private static JsonNode json(SseTestClient.Event event) {
        return MAPPER.readTree(event.data());
    }

    private static FakeAgentServer start() {
        try {
            return new FakeAgentServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
