package com.hanstep.aijob.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** INT-08, INT-10. 실제 DB, 테스트마다 롤백. 작업은 테스트 안에서 새로 넣는다. */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class AiJobInternalApiTest {

    private static final String STARTED = "{\"workerId\":\"policy-worker-1\",\"startedAt\":\"2026-11-10T10:00:00+09:00\"}";
    private static final String FAILED =
            "{\"errorCode\":\"EXTRACTION_FAILED\",\"message\":\"scanned PDF needs OCR\",\"retryable\":false}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    // ---------- INT-08 ----------

    @Test
    void PENDING_작업을_시작하면_PROCESSING과_시작_시각() throws Exception {
        long id = insertJob("PENDING");

        started(id).andExpect(status().isNoContent());

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(row.get("started_at")).isNotNull();
    }

    @Test
    void QUEUED_작업을_시작하면_PROCESSING() throws Exception {
        long id = insertJob("QUEUED");

        started(id).andExpect(status().isNoContent());

        assertThat(row(id).get("status")).isEqualTo("PROCESSING");
    }

    @Test
    void 시작_시각은_worker가_보낸_값이_아니라_받은_시각() throws Exception {
        long id = insertJob("QUEUED");

        perform("/internal/v1/ai/jobs/{id}/started", id,
                "{\"workerId\":\"policy-worker-1\",\"startedAt\":\"2020-01-01T00:00:00+09:00\"}")
                .andExpect(status().isNoContent());

        row(id);
        Boolean recent = jdbcTemplate.queryForObject(
                "SELECT started_at > '2025-01-01' FROM ai_jobs WHERE id = ?", Boolean.class, id);
        assertThat(recent).isTrue();
    }

    @Test
    void 이미_시작한_작업을_다시_시작하면_409() throws Exception {
        long id = insertJob("QUEUED");
        started(id).andExpect(status().isNoContent());

        started(id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 완료된_작업을_시작하면_409() throws Exception {
        long id = insertJob("COMPLETED");

        started(id).andExpect(status().isConflict());
    }

    @Test
    void 실패한_작업을_시작하면_409() throws Exception {
        long id = insertJob("FAILED");

        started(id).andExpect(status().isConflict());
    }

    @Test
    void 없는_작업을_시작하면_404() throws Exception {
        started(999999999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 시작_본문에_startedAt이_없으면_400() throws Exception {
        long id = insertJob("QUEUED");

        perform("/internal/v1/ai/jobs/{id}/started", id, "{\"workerId\":\"w\"}")
                .andExpect(status().isBadRequest());
    }

    // ---------- INT-10 ----------

    @Test
    void 실패를_기록한다() throws Exception {
        long id = insertJob("PROCESSING");

        failed(id, FAILED).andExpect(status().isNoContent());

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_code")).isEqualTo("EXTRACTION_FAILED");
        assertThat(row.get("error_message")).isEqualTo("scanned PDF needs OCR");
        assertThat(row.get("completed_at")).isNotNull();
    }

    @Test
    void 같은_실패를_다시_보내면_204이고_종료_시각은_그대로() throws Exception {
        long id = insertJob("PROCESSING");
        failed(id, FAILED).andExpect(status().isNoContent());
        Object completedAt = row(id).get("completed_at");

        failed(id, FAILED).andExpect(status().isNoContent());

        assertThat(row(id).get("completed_at")).isEqualTo(completedAt);
    }

    @Test
    void 실패한_작업에_다른_실패가_오면_마지막_실패로_덮어쓰고_204() throws Exception {
        long id = insertJob("PROCESSING");
        failed(id, FAILED).andExpect(status().isNoContent());

        failed(id, "{\"errorCode\":\"VECTOR_STORE_FAILED\",\"message\":\"mongo timeout\",\"retryable\":true}")
                .andExpect(status().isNoContent());

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_code")).isEqualTo("VECTOR_STORE_FAILED");
        assertThat(row.get("error_message")).isEqualTo("mongo timeout");
    }

    @Test
    void 완료된_작업에_실패가_오면_무시하고_204() throws Exception {
        long id = insertJob("COMPLETED");

        failed(id, FAILED).andExpect(status().isNoContent());

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("error_code")).isNull();
    }

    @Test
    void 없는_작업에_실패를_보내면_404() throws Exception {
        failed(999999999L, FAILED)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 실패_메시지가_500자를_넘으면_400() throws Exception {
        long id = insertJob("PROCESSING");

        failed(id, failedBody("x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("message"));
    }

    // AI 는 message[:500] 으로 자른다(코드포인트). 이모지 500자는 UTF-16 으로 1000자다.
    @Test
    void 이모지_500자_메시지는_받는다() throws Exception {
        long id = insertJob("PROCESSING");
        String message = "😀".repeat(500);

        failed(id, failedBody(message)).andExpect(status().isNoContent());

        assertThat(row(id).get("error_message")).isEqualTo(message);
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        long id = insertJob("QUEUED");

        mockMvc.perform(post("/internal/v1/ai/jobs/{id}/started", id)
                        .contentType(MediaType.APPLICATION_JSON).content(STARTED))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private ResultActions started(long id) throws Exception {
        return perform("/internal/v1/ai/jobs/{id}/started", id, STARTED);
    }

    private ResultActions failed(long id, String body) throws Exception {
        return perform("/internal/v1/ai/jobs/{id}/failed", id, body);
    }

    private static String failedBody(String message) {
        return "{\"errorCode\":\"EXTRACTION_FAILED\",\"message\":\"%s\",\"retryable\":false}".formatted(message);
    }

    private ResultActions perform(String url, long id, String body) throws Exception {
        return mockMvc.perform(post(url, id)
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long insertJob(String status) {
        boolean done = status.equals("COMPLETED") || status.equals("FAILED");
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO ai_jobs (job_type, resource_type, resource_id, idempotency_key, correlation_id,
                      status, completed_at)
                    VALUES ('POLICY_INGEST', 'POLICY_DOCUMENT', 1, ?, 'req_test', ?, %s)
                    """.formatted(done ? "CURRENT_TIMESTAMP(3)" : "NULL"), Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "test-" + UUID.randomUUID());
            ps.setString(2, status);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    // 테스트 트랜잭션 안이라 JPA 변경을 먼저 내려보내야 JDBC 로 보인다
    private Map<String, Object> row(long id) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("SELECT * FROM ai_jobs WHERE id = ?", id);
    }
}
