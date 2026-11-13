package com.hanstep.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 브로커에 연결할 수 없을 때. 아무도 듣지 않는 포트로 보내 실제 연결 실패를 만든다.
 * 등록은 이미 커밋됐으므로 201 그대로이고 작업만 PUBLISH_FAILED 로 남는다. 실제로 커밋하고 끝나고 지운다.
 */
@SpringBootTest(properties = {InternalApiTestSupport.KEY_PROPERTY, "spring.rabbitmq.connection-timeout=1s"})
@AutoConfigureMockMvc
class LawDocumentRegisterBrokerDownTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @DynamicPropertySource
    static void unreachableBroker(DynamicPropertyRegistry registry) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> closedPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String hash = randomHash();

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ai_jobs WHERE job_type = 'POLICY_INGEST' AND idempotency_key = ?", hash);
        jdbcTemplate.update("DELETE FROM policy_documents WHERE content_hash = ?", hash);
    }

    @Test
    void 브로커에_연결하지_못해도_등록은_201이고_작업은_PUBLISH_FAILED() throws Exception {
        String externalId = String.valueOf(100_000_000 + RANDOM.nextInt(900_000_000));

        mockMvc.perform(post("/internal/v1/ai-context/policy-documents")
                        .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY)
                        .header("Idempotency-Key", hash)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceType":"LAW","title":"출입국관리법 시행규칙","issuingAgency":"법무부",
                                 "sourceUrl":"https://www.law.go.kr/test","externalId":"%s","visaTypeCode":null,
                                 "publishedOn":null,"effectiveFrom":"2026-10-23","contentHash":"%s","objectKey":null}
                                """.formatted(externalId, hash)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewStatus").value("DRAFT"));

        Map<String, Object> job = jdbcTemplate.queryForMap(
                "SELECT * FROM ai_jobs WHERE job_type = 'POLICY_INGEST' AND idempotency_key = ?", hash);
        assertThat(job.get("publish_status")).isEqualTo("PUBLISH_FAILED");
        assertThat(job.get("status")).isEqualTo("PENDING");
        assertThat(job.get("queued_at")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_status FROM policy_documents WHERE content_hash = ?", String.class, hash))
                .isEqualTo("DRAFT");
    }

    private static String randomHash() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
