package com.hanstep.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** CHT-01·02·03. 테스트 사용자·대화방·메시지를 직접 넣고 테스트마다 롤백. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatSessionApiTest {

    private static final String SESSIONS = "/api/v1/chat/sessions";
    private static final String KST_TIME = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+]09:00";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        userId = createUser("test-chat-a");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    @Test
    void 대화방_생성은_201과_ACTIVE() throws Exception {
        mockMvc.perform(post(SESSIONS).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value(nullValue()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lastMessageAt").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(KST_TIME)));

        // 본문 없이도 생성, 제목 앞뒤 공백은 자름
        mockMvc.perform(post(SESSIONS).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isCreated());
        mockMvc.perform(post(SESSIONS).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  Rent  \"}"))
                .andExpect(jsonPath("$.title").value("Rent"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE user_id = ?", Long.class, userId))
                .isEqualTo(3);
    }

    @Test
    void 제목_200자_초과는_400_토큰_없으면_401() throws Exception {
        mockMvc.perform(post(SESSIONS).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + "a".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post(SESSIONS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(SESSIONS)).andExpect(status().isUnauthorized());
    }

    @Test
    void 목록은_본인_ACTIVE만_최근순과_커서() throws Exception {
        long old = session(userId, "ACTIVE", "2026-11-01T00:00:00", null);
        long recentMessage = session(userId, "ACTIVE", "2026-10-01T00:00:00", "2026-11-10T00:00:00");
        long newEmpty = session(userId, "ACTIVE", "2026-11-05T00:00:00", null);
        long tieLow = session(userId, "ACTIVE", "2026-11-03T00:00:00", null);
        long tieHigh = session(userId, "ACTIVE", "2026-11-03T00:00:00", null);
        session(userId, "ARCHIVED", "2026-11-11T00:00:00", null);
        session(createUser("test-chat-b"), "ACTIVE", "2026-11-11T00:00:00", null);

        JsonNode first = json(mockMvc.perform(get(SESSIONS).param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.hasNext").value(true))
                .andExpect(jsonPath("$.sessions[0].lastMessageAt").value(matchesPattern(KST_TIME))));
        assertThat(ids(first.path("sessions"))).containsExactly(recentMessage, newEmpty);

        JsonNode second = json(mockMvc.perform(get(SESSIONS).param("size", "2")
                .param("cursor", first.path("page").path("nextCursor").asString())
                .header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()));
        assertThat(ids(second.path("sessions"))).containsExactly(tieHigh, tieLow); // 같은 시각이면 id 큰 순
        assertThat(second.path("page").path("hasNext").asBoolean()).isTrue();

        JsonNode third = json(mockMvc.perform(get(SESSIONS).param("size", "2")
                .param("cursor", second.path("page").path("nextCursor").asString())
                .header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()));
        assertThat(ids(third.path("sessions"))).containsExactly(old);
        assertThat(third.path("page").path("hasNext").asBoolean()).isFalse();
        assertThat(third.path("page").path("nextCursor").isNull()).isTrue();
    }

    @Test
    void 목록_size_cursor_검증은_400() throws Exception {
        mockMvc.perform(get(SESSIONS).param("size", "0").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("size"));
        mockMvc.perform(get(SESSIONS).param("size", "101").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(SESSIONS).param("cursor", "!!bad").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("cursor"));
    }

    @Test
    void 메시지는_최신_size개를_오래된_순으로_커서로_이전() throws Exception {
        long sessionId = session(userId, "ACTIVE", "2026-11-01T00:00:00", "2026-11-01T00:05:00");
        long m1 = message(sessionId, "USER", "q1", "COMPLETED", "c1", "run_1", null, "2026-11-01T00:01:00");
        long m2 = message(sessionId, "ASSISTANT", "a1", "COMPLETED", null, "run_1", m1, "2026-11-01T00:01:00");
        long m3 = message(sessionId, "USER", "q2", "COMPLETED", "c2", "run_2", null, "2026-11-01T00:02:00");
        long m4 = message(sessionId, "ASSISTANT", "a2", "FAILED", null, "run_2", m3, "2026-11-01T00:02:00");
        jdbc.update("UPDATE chat_messages SET blocks_json = '[{\"type\":\"CARD\"}]', model_id = 'm', latency_ms = 1234 "
                + "WHERE id = ?", m2);
        jdbc.update("INSERT INTO chat_message_sources (message_id, source_type, source_id, chunk_ref, page_label) "
                + "VALUES (?, 'FINANCIAL_ANALYSIS', 7, NULL, NULL), (?, 'POLICY_DOCUMENT', 3, 'chunk-1', 'p.2')", m2, m2);

        JsonNode latest = json(mockMvc.perform(get(SESSIONS + "/" + sessionId + "/messages").param("size", "3")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.messages[0].content").value("a1"))
                .andExpect(jsonPath("$.messages[0].blocks[0].type").value("CARD"))
                .andExpect(jsonPath("$.messages[0].clientMessageId").value(nullValue()))
                .andExpect(jsonPath("$.messages[0].replyToId").value(m1))
                .andExpect(jsonPath("$.messages[0].runId").value("run_1"))
                .andExpect(jsonPath("$.messages[0].latencyMs").value(1234))
                .andExpect(jsonPath("$.messages[0].sources.length()").value(2))
                .andExpect(jsonPath("$.messages[0].sources[0].type").value("FINANCIAL_ANALYSIS"))
                .andExpect(jsonPath("$.messages[0].sources[0].id").value(7))
                .andExpect(jsonPath("$.messages[0].sources[1].chunkRef").value("chunk-1"))
                .andExpect(jsonPath("$.messages[0].sources[1].pageLabel").value("p.2"))
                .andExpect(jsonPath("$.messages[1].clientMessageId").value("c2"))
                .andExpect(jsonPath("$.messages[1].blocks").value(nullValue()))
                .andExpect(jsonPath("$.messages[1].sources.length()").value(0))
                .andExpect(jsonPath("$.messages[2].status").value("FAILED"))
                .andExpect(jsonPath("$.messages[2].createdAt").value(matchesPattern(KST_TIME)))
                .andExpect(jsonPath("$.page.hasNext").value(true)));
        assertThat(ids(latest.path("messages"))).containsExactly(m2, m3, m4);

        JsonNode older = json(mockMvc.perform(get(SESSIONS + "/" + sessionId + "/messages").param("size", "3")
                .param("cursor", latest.path("page").path("nextCursor").asString())
                .header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()));
        assertThat(ids(older.path("messages"))).containsExactly(m1);
        assertThat(older.path("page").path("hasNext").asBoolean()).isFalse();
    }

    @Test
    void 남의_대화방과_없는_대화방_메시지는_404() throws Exception {
        long others = session(createUser("test-chat-b"), "ACTIVE", "2026-11-01T00:00:00", null);
        mockMvc.perform(get(SESSIONS + "/" + others + "/messages").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get(SESSIONS + "/999999999/messages").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
    }

    private long createUser(String key) {
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES (?, 'TEST CHAT')", key);
        return jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class, key);
    }

    private long session(long owner, String status, String createdAt, String lastMessageAt) {
        jdbc.update("INSERT INTO chat_sessions (user_id, status, created_at, last_message_at) VALUES (?, ?, ?, ?)",
                owner, status, LocalDateTime.parse(createdAt),
                lastMessageAt == null ? null : LocalDateTime.parse(lastMessageAt));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long message(long sessionId, String role, String content, String status, String clientId, String runId,
                         Long replyTo, String createdAt) {
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, status, client_message_id, run_id, "
                        + "reply_to_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, role, content, status, clientId, runId, replyTo, LocalDateTime.parse(createdAt));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode array) {
        return array.valueStream().map(n -> n.path("id").asLong()).toList();
    }
}
