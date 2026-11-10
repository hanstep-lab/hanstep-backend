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
    void 대기_중인_작업을_시작하면_PROCESSING과_시작_시각() throws Exception {
        for (String status : new String[] {"PENDING", "QUEUED"}) {
            long id = insertJob(status);

            started(id).andExpect(status().isNoContent());

            Map<String, Object> row = row(id);
            assertThat(row.get("status")).isEqualTo("PROCESSING");
            assertThat(row.get("started_at")).isNotNull();
        }
    }

    @Test
    void 이미_시작했거나_끝난_작업이면_409() throws Exception {
        long id = insertJob("QUEUED");
        started(id).andExpect(status().isNoContent());
        started(id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        long completed = insertJob("COMPLETED");
        started(completed).andExpect(status().isConflict());
        long failed = insertJob("FAILED");
        started(failed).andExpect(status().isConflict());
    }

    @Test
    void 없는_작업이면_404() throws Exception {
        started(999999999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        failed(999999999L, FAILED).andExpect(status().isNotFound());
    }

    @Test
    void 시작_본문_형식이_틀리면_400() throws Exception {
        long id = insertJob("QUEUED");
        perform("/internal/v1/ai/jobs/{id}/started", id, "{\"workerId\":\"w\"}").andExpect(status().isBadRequest());
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
    void 같은_실패를_다시_보내면_204() throws Exception {
        long id = insertJob("PROCESSING");
        failed(id, FAILED).andExpect(status().isNoContent());
        Object completedAt = row(id).get("completed_at");

        failed(id, FAILED).andExpect(status().isNoContent());

        assertThat(row(id).get("completed_at")).isEqualTo(completedAt);
    }

    @Test
    void 끝난_작업에_다른_실패가_오면_409() throws Exception {
        long id = insertJob("PROCESSING");
        failed(id, FAILED).andExpect(status().isNoContent());
        failed(id, "{\"errorCode\":\"NEEDS_OCR\",\"message\":\"other\",\"retryable\":false}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        long completed = insertJob("COMPLETED");
        failed(completed, FAILED).andExpect(status().isConflict());
    }

    @Test
    void 실패_메시지가_500자를_넘으면_400() throws Exception {
        long id = insertJob("PROCESSING");
        String body = "{\"errorCode\":\"EXTRACTION_FAILED\",\"message\":\"%s\",\"retryable\":false}"
                .formatted("x".repeat(501));
        failed(id, body).andExpect(status().isBadRequest());
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
