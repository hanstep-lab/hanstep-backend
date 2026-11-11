package com.hanstep.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.chat.support.SseTestClient;
import com.hanstep.chat.support.SseTestClient.Result;
import com.hanstep.common.security.JwtTokenProvider;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AI 서버에 연결할 수 없을 때(연결 거부) CHT-04 는 chat.error AI_UPSTREAM_ERROR + FAILED. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hanstep.ai.base-url=http://127.0.0.1:1", "hanstep.ai.connect-timeout=1s"})
class ChatStreamAiDownTest {

    private static final String USER_KEY = "test-chat-down";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE external_user_key = ?", USER_KEY);
    }

    @Test
    void AI_연결_거부는_chat_error와_FAILED() throws Exception {
        cleanUp();
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES (?, 'TEST')", USER_KEY);
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class, USER_KEY);
        jdbc.update("INSERT INTO chat_sessions (user_id) VALUES (?)", userId);
        long sessionId = jdbc.queryForObject("SELECT id FROM chat_sessions WHERE user_id = ?", Long.class, userId);

        Result result = new SseTestClient(HttpClient.newHttpClient(), "http://127.0.0.1:" + port)
                .stream("Bearer " + jwtTokenProvider.issue(userId), sessionId, SseTestClient.body("c-1", "hi"), null);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.names()).containsExactly("chat.started", "chat.error");
        JsonNode error = JsonMapper.builder().build().readTree(result.first("chat.error").data());
        assertThat(error.path("code").asString()).isEqualTo("AI_UPSTREAM_ERROR");
        long assistantId = JsonMapper.builder().build().readTree(result.first("chat.started").data())
                .path("assistantMessageId").asLong();
        Map<String, Object> row = jdbc.queryForMap("SELECT status, error_code FROM chat_messages WHERE id = ?",
                assistantId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_code")).isEqualTo("AI_UPSTREAM_ERROR");
    }
}
