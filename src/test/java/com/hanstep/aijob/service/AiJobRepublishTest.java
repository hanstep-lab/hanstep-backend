package com.hanstep.aijob.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.aijob.RabbitTestBroker;
import com.hanstep.aijob.messaging.AiJobRoute;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * U2 발행 대기 작업 재발행. 실제 MySQL(커밋, 끝나고 넣은 작업만 지움) + Testcontainers RabbitMQ.
 * 스캔(findStale)은 공용 DB 의 다른 행도 볼 수 있어 "내 작업이 들어 있는가/빠졌는가"만 본다. 주기 스캔 실행은 하지 않는다
 * (다른 사람의 대기 작업까지 보내지 않게). 테스트 실행에서는 스케줄러 자체가 꺼져 있다(build.gradle).
 */
@SpringBootTest
class AiJobRepublishTest {

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        RabbitTestBroker.register(registry);
    }

    @Autowired
    private AiJobRepublishService republishService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ApplicationContext context;

    private final String keyPrefix = "test-republish-" + UUID.randomUUID() + "-";

    @BeforeEach
    void setUp() {
        rabbitAdmin.initialize();
        for (AiJobRoute route : AiJobRoute.values()) {
            rabbitAdmin.purgeQueue(route.routingKey(), false);
        }
    }

    @AfterEach
    void tearDown() {
        rabbitAdmin.initialize();
        jdbcTemplate.update("DELETE FROM ai_jobs WHERE idempotency_key LIKE ?", keyPrefix + "%");
    }

    @Test
    void 테스트_실행에서는_주기_스캔_스케줄러가_없다() {
        assertThat(context.getBeanNamesForType(AiJobRepublishScheduler.class)).isEmpty();
    }

    @Test
    void 오래된_발행_대기_작업은_스캔에_잡히고_같은_jobId로_다시_보내_PUBLISHED() throws Exception {
        long ocr = insertJob("OCR", "DOCUMENT", "PENDING", "PUBLISH_FAILED", 60);
        long policy = insertJob("POLICY_INGEST", "POLICY_DOCUMENT", "PENDING", "NOT_PUBLISHED", 60);

        assertThat(republishService.findStale(30, 10_000)).contains(ocr, policy);
        assertThat(republishService.republish(ocr, 30)).isTrue();
        assertThat(republishService.republish(policy, 30)).isTrue();

        for (long jobId : List.of(ocr, policy)) {
            Map<String, Object> row = row(jobId);
            assertThat(row.get("publish_status")).isEqualTo("PUBLISHED");
            assertThat(row.get("status")).isEqualTo("QUEUED");
            assertThat(row.get("queued_at")).isNotNull();
            assertThat(((Number) row.get("retry_count")).intValue()).isZero();
        }
        assertThat(jobIdIn(AiJobRoute.OCR.routingKey())).isEqualTo(ocr);
        assertThat(jobIdIn(AiJobRoute.POLICY_INGEST.routingKey())).isEqualTo(policy);
        assertThat(count("SELECT COUNT(*) FROM ai_jobs WHERE idempotency_key LIKE ?")).isEqualTo(2);
    }

    @Test
    void 방금_바뀐_작업은_스캔이_건너뛰고_사용자_다시_시도는_바로_보낸다() throws Exception {
        long fresh = insertJob("OCR", "DOCUMENT", "PENDING", "PUBLISH_FAILED", 0);

        assertThat(republishService.findStale(30, 10_000)).doesNotContain(fresh);
        assertThat(republishService.republish(fresh, 30)).isFalse();
        assertThat(row(fresh).get("publish_status")).isEqualTo("PUBLISH_FAILED");

        assertThat(republishService.republish(fresh, null)).isTrue();
        assertThat(row(fresh).get("publish_status")).isEqualTo("PUBLISHED");
        assertThat(jobIdIn(AiJobRoute.OCR.routingKey())).isEqualTo(fresh);
    }

    @Test
    void 발행된_작업_처리_중_끝난_작업_경로_없는_작업은_다시_보내지_않는다() {
        long queued = insertJob("OCR", "DOCUMENT", "QUEUED", "PUBLISHED", 60);
        long published = insertJob("OCR", "DOCUMENT", "PENDING", "PUBLISHED", 60);
        long processing = insertJob("OCR", "DOCUMENT", "PROCESSING", "PUBLISH_FAILED", 60);
        long noRoute = insertJob("DOCUMENT_ANALYSIS", "DOCUMENT", "PENDING", "NOT_PUBLISHED", 60);

        assertThat(republishService.findStale(30, 10_000)).doesNotContain(queued, published, processing, noRoute);
        for (long jobId : List.of(queued, published, processing, noRoute)) {
            assertThat(republishService.republish(jobId, null)).isFalse();
        }
        assertThat(rabbitTemplate.receive(AiJobRoute.OCR.routingKey(), 500)).isNull();
    }

    @Test
    void 다시_보내도_실패하면_PUBLISH_FAILED로_남고_30초_동안은_스캔이_다시_집지_않는다() {
        long jobId = insertJob("OCR", "DOCUMENT", "PENDING", "NOT_PUBLISHED", 60);
        rabbitAdmin.deleteQueue(AiJobRoute.OCR.routingKey());   // 라우팅 안 됨 → 반환 → PUBLISH_FAILED

        assertThat(republishService.republish(jobId, 30)).isTrue();

        Map<String, Object> row = row(jobId);
        assertThat(row.get("publish_status")).isEqualTo("PUBLISH_FAILED");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(republishService.findStale(30, 10_000)).doesNotContain(jobId);
    }

    // ---------- helpers ----------

    /** 작업 1건. idleSeconds 만큼 전에 마지막으로 바뀐 것처럼 updated_at 을 둔다. */
    private long insertJob(String jobType, String resourceType, String status, String publishStatus, int idleSeconds) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO ai_jobs (job_type, resource_type, resource_id, idempotency_key, correlation_id, status,
                      publish_status, queued_at, started_at, updated_at)
                    VALUES (?, ?, ?, ?, 'req_republish', ?, ?, %s, %s, CURRENT_TIMESTAMP(3) - INTERVAL ? SECOND)
                    """.formatted(publishStatus.equals("PUBLISHED") ? "CURRENT_TIMESTAMP(3)" : "NULL",
                    status.equals("PROCESSING") ? "CURRENT_TIMESTAMP(3)" : "NULL"), Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, jobType);
            ps.setString(2, resourceType);
            ps.setLong(3, 900_000_000L + (long) (Math.random() * 99_999_999));
            ps.setString(4, keyPrefix + UUID.randomUUID());
            ps.setString(5, status);
            ps.setString(6, publishStatus);
            ps.setInt(7, idleSeconds);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private Map<String, Object> row(long jobId) {
        return jdbcTemplate.queryForMap("SELECT * FROM ai_jobs WHERE id = ?", jobId);
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class, keyPrefix + "%");
    }

    private long jobIdIn(String queue) throws Exception {
        Message message = rabbitTemplate.receive(queue, 5_000);
        assertThat(message).as("message in " + queue).isNotNull();
        return JsonMapper.builder().build().readTree(message.getBody()).path("jobId").longValue();
    }
}
