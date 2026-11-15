package com.hanstep.policy.controller;

import static com.hanstep.aijob.RabbitTestBroker.WORK_QUEUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.aijob.RabbitTestBroker;
import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.web.RequestIdFilter;
import com.jayway.jsonpath.JsonPath;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI-R6 등록 → 커밋 → POLICY_INGEST 발행. 실제 MySQL + Testcontainers RabbitMQ, 실제로 커밋하고 끝나고 지운다.
 */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
class LawDocumentRegisterPublishTest {

    private static final String URL = "/internal/v1/ai-context/policy-documents";
    private static final SecureRandom RANDOM = new SecureRandom();

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        RabbitTestBroker.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    private final String hash = randomHash();
    private final String externalId = String.valueOf(100_000_000 + RANDOM.nextInt(900_000_000));

    @BeforeEach
    void setUp() {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(WORK_QUEUE, false);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ai_jobs WHERE job_type = 'POLICY_INGEST' AND idempotency_key = ?", hash);
        jdbcTemplate.update("DELETE FROM policy_documents WHERE content_hash = ?", hash);
    }

    @Test
    void 새_법령을_등록하면_커밋_후_POLICY_INGEST를_보내고_QUEUED() throws Exception {
        long documentId = idOf(register("req_lawpub01").andExpect(status().isCreated()));

        Map<String, Object> job = job();
        assertThat(job.get("publish_status")).isEqualTo("PUBLISHED");
        assertThat(job.get("status")).isEqualTo("QUEUED");
        assertThat(job.get("queued_at")).isNotNull();

        Message message = rabbitTemplate.receive(WORK_QUEUE, 5_000);
        assertThat(message).isNotNull();
        JsonNode body = JsonMapper.builder().build().readTree(message.getBody());
        assertThat(body.path("jobId").longValue()).isEqualTo(((Number) job.get("id")).longValue());
        assertThat(body.path("resourceType").stringValue()).isEqualTo("POLICY_DOCUMENT");
        assertThat(body.path("resourceId").longValue()).isEqualTo(documentId);
        assertThat(body.path("requestId").stringValue()).isEqualTo("req_lawpub01");
        // 본문·URL 같은 문서 내용은 메시지에 없다
        assertThat(new String(message.getBody())).doesNotContain(hash, "law.go.kr", "출입국");
    }

    @Test
    void 같은_법령을_다시_보내면_200이고_작업을_새로_만들거나_다시_보내지_않는다() throws Exception {
        long documentId = idOf(register("req_lawpub02").andExpect(status().isCreated()));
        assertThat(rabbitTemplate.receive(WORK_QUEUE, 5_000)).isNotNull();

        long again = idOf(register("req_lawpub03").andExpect(status().isOk()));

        assertThat(again).isEqualTo(documentId);
        List<Map<String, Object>> jobs = jdbcTemplate.queryForList(
                "SELECT * FROM ai_jobs WHERE job_type = 'POLICY_INGEST' AND idempotency_key = ?", hash);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().get("correlation_id")).isEqualTo("req_lawpub02");
        assertThat(Objects.requireNonNull(rabbitAdmin.getQueueInfo(WORK_QUEUE)).getMessageCount()).isZero();
    }

    private ResultActions register(String requestId) throws Exception {
        return mockMvc.perform(post(URL)
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY)
                .header("Idempotency-Key", hash)
                .header(RequestIdFilter.HEADER, requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceType":"LAW","title":"출입국관리법 시행규칙","issuingAgency":"법무부",
                         "sourceUrl":"https://www.law.go.kr/test","externalId":"%s","visaTypeCode":null,
                         "publishedOn":null,"effectiveFrom":"2026-10-23","contentHash":"%s","objectKey":null}
                        """.formatted(externalId, hash)));
    }

    private Map<String, Object> job() {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM ai_jobs WHERE job_type = 'POLICY_INGEST' AND idempotency_key = ?", hash);
    }

    private static long idOf(ResultActions result) throws Exception {
        Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.policyDocumentId");
        return id.longValue();
    }

    private static String randomHash() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
