package com.hanstep.aijob.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.hanstep.aijob.domain.AiJobResourceType;
import com.hanstep.aijob.domain.AiJobType;
import com.hanstep.aijob.dto.JobStartedRequest;
import com.hanstep.aijob.service.AiJobEnqueueService;
import com.hanstep.aijob.service.AiJobStatusService;
import java.net.ConnectException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * confirm 타이밍. RabbitTemplate 만 가짜로 두고 confirm 을 테스트가 직접 완료한다. DB·트랜잭션 이벤트는 실제로 지나간다.
 * 커밋한 스레드가 confirm 을 기다리는 동안 다른 스레드에서 상태를 본다. 넣은 작업은 끝나고 지운다.
 */
@SpringBootTest(properties = "hanstep.ai-jobs.confirm-timeout=2s")
class AiJobPublishConfirmTest {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoSpyBean
    private AiJobStatusService statusService;

    @Autowired
    private AiJobEnqueueService enqueueService;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String key = "test-confirm-" + UUID.randomUUID();
    private final BlockingQueue<CorrelationData> sent = new LinkedBlockingQueue<>();

    @BeforeEach
    void captureSends() {
        doAnswer(invocation -> sent.add(invocation.getArgument(3)))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ai_jobs WHERE idempotency_key = ?", key);
    }

    @Test
    void ACK_전에는_PUBLISHED가_아니고_ACK_후에_PUBLISHED_QUEUED() throws Exception {
        CompletableFuture<Long> committing = commitInBackground();
        CorrelationData correlation = awaitSend();

        // 커밋은 끝났고 confirm 을 기다리는 중
        Map<String, Object> waiting = row();
        assertThat(waiting.get("publish_status")).isEqualTo("NOT_PUBLISHED");
        assertThat(waiting.get("status")).isEqualTo("PENDING");
        assertThat(waiting.get("queued_at")).isNull();

        correlation.getFuture().complete(new Confirm(true, null));
        committing.get(5, TimeUnit.SECONDS);

        Map<String, Object> published = row();
        assertThat(published.get("publish_status")).isEqualTo("PUBLISHED");
        assertThat(published.get("status")).isEqualTo("QUEUED");
        assertThat(published.get("queued_at")).isNotNull();
    }

    @Test
    void worker가_먼저_시작하면_늦은_ACK가_PROCESSING을_되돌리지_않는다() throws Exception {
        CompletableFuture<Long> committing = commitInBackground();
        CorrelationData correlation = awaitSend();
        long jobId = ((Number) row().get("id")).longValue();

        statusService.markStarted(jobId, new JobStartedRequest("policy-worker-1", OffsetDateTime.now()));
        correlation.getFuture().complete(new Confirm(true, null));
        committing.get(5, TimeUnit.SECONDS);

        Map<String, Object> row = row();
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(row.get("publish_status")).isEqualTo("PUBLISHED");
        assertThat(row.get("queued_at")).isNotNull();
        assertThat(row.get("started_at")).isNotNull();
    }

    @Test
    void NACK이면_PUBLISH_FAILED() throws Exception {
        CompletableFuture<Long> committing = commitInBackground();
        awaitSend().getFuture().complete(new Confirm(false, "test nack"));
        committing.get(5, TimeUnit.SECONDS);

        assertFailed();
    }

    @Test
    void confirm이_시간_안에_오지_않으면_PUBLISH_FAILED() throws Exception {
        CompletableFuture<Long> committing = commitInBackground();
        awaitSend();   // 완료하지 않는다

        committing.get(10, TimeUnit.SECONDS);

        assertFailed();
    }

    @Test
    void 보내다가_연결_오류면_PUBLISH_FAILED() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatNoException().isThrownBy(() -> tx.execute(status -> enqueue()));

        assertFailed();
    }

    @Test
    void 발행_상태를_기록하지_못해도_커밋한_쪽에는_예외가_없다() throws Exception {
        doThrow(new IllegalStateException("db down")).when(statusService).markPublished(anyLong());

        CompletableFuture<Long> committing = commitInBackground();
        awaitSend().getFuture().complete(new Confirm(true, null));

        assertThat(committing.get(5, TimeUnit.SECONDS)).isPositive();
        assertThat(row().get("publish_status")).isEqualTo("NOT_PUBLISHED");
    }

    // ---------- helpers ----------

    private CompletableFuture<Long> commitInBackground() {
        return CompletableFuture.supplyAsync(() -> tx.execute(status -> enqueue()));
    }

    private long enqueue() {
        return enqueueService.enqueue(AiJobType.POLICY_INGEST, AiJobResourceType.POLICY_DOCUMENT, 999_999_999L, null,
                key).getId();
    }

    private CorrelationData awaitSend() throws InterruptedException {
        CorrelationData correlation = sent.poll(5, TimeUnit.SECONDS);
        assertThat(correlation).as("RabbitTemplate.send 호출").isNotNull();
        return correlation;
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap("SELECT * FROM ai_jobs WHERE idempotency_key = ?", key);
    }

    private void assertFailed() {
        Map<String, Object> row = row();
        assertThat(row.get("publish_status")).isEqualTo("PUBLISH_FAILED");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("queued_at")).isNull();
    }
}
