package com.hanstep.aijob.messaging;

import static com.hanstep.aijob.RabbitTestBroker.DEAD_LETTER_QUEUE;
import static com.hanstep.aijob.RabbitTestBroker.RETRY_QUEUE;
import static com.hanstep.aijob.RabbitTestBroker.WORK_QUEUE;
import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.aijob.RabbitTestBroker;
import com.hanstep.aijob.domain.AiJobResourceType;
import com.hanstep.aijob.domain.AiJobType;
import com.hanstep.aijob.service.AiJobEnqueueService;
import com.rabbitmq.client.GetResponse;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 커밋 후 발행과 큐 구성. 실제 MySQL + Testcontainers RabbitMQ. 트랜잭션 이벤트 경계를 그대로 지나야 해서 실제로 커밋하고,
 * 넣은 작업은 끝나고 지운다. NACK·NO_ROUTE 는 브로커 쪽 큐를 바꿔 실제로 만든다.
 */
@SpringBootTest
class AiJobPublishBrokerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        RabbitTestBroker.register(registry);
    }

    @Autowired
    private AiJobEnqueueService enqueueService;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String keyPrefix = "test-publish-" + UUID.randomUUID() + "-";

    @BeforeEach
    void setUp() {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(WORK_QUEUE, false);
        rabbitAdmin.purgeQueue(RETRY_QUEUE, false);
        rabbitAdmin.purgeQueue(DEAD_LETTER_QUEUE, false);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ai_jobs WHERE idempotency_key LIKE ?", keyPrefix + "%");
    }

    @Test
    void 커밋_전에는_보내지_않고_커밋하면_보낸_뒤_PUBLISHED와_QUEUED() throws Exception {
        long jobId = tx.execute(status -> {
            long id = enqueue("a");
            assertThat(messageCount(WORK_QUEUE)).isZero();
            assertThat(row(id).get("publish_status")).isEqualTo("NOT_PUBLISHED");
            return id;
        });

        Map<String, Object> row = row(jobId);
        assertThat(row.get("publish_status")).isEqualTo("PUBLISHED");
        assertThat(row.get("status")).isEqualTo("QUEUED");
        assertThat(row.get("queued_at")).isNotNull();

        Message message = rabbitTemplate.receive(WORK_QUEUE, 5_000);
        assertThat(message).isNotNull();
        JsonNode body = MAPPER.readTree(message.getBody());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "schemaVersion", "jobId", "jobType", "resourceType", "resourceId", "requestId");
        assertThat(body.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(body.path("jobId").longValue()).isEqualTo(jobId);
        assertThat(body.path("jobType").stringValue()).isEqualTo("POLICY_INGEST");
        assertThat(body.path("resourceType").stringValue()).isEqualTo("POLICY_DOCUMENT");
        assertThat(body.path("resourceId").longValue()).isEqualTo(((Number) row.get("resource_id")).longValue());
        // HTTP 요청 밖에서 만든 작업이라 새 추적 ID. 저장한 correlation_id 와 같은 값을 보낸다
        assertThat(body.path("requestId").stringValue()).isEqualTo(row.get("correlation_id")).startsWith("job_");

        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(String.valueOf(jobId));
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getReceivedExchange()).isEqualTo(AiJobRoute.EXCHANGE);
        assertThat(properties.getReceivedRoutingKey()).isEqualTo(WORK_QUEUE);
    }

    @Test
    void 롤백하면_보내지_않고_작업도_남지_않는다() {
        long[] jobId = new long[1];
        tx.executeWithoutResult(status -> {
            jobId[0] = enqueue("a");
            status.setRollbackOnly();
        });

        assertThat(messageCount(WORK_QUEUE)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_jobs WHERE id = ?", Integer.class, jobId[0]))
                .isZero();
    }

    @Test
    void 큐로_라우팅되지_않으면_ACK가_와도_PUBLISH_FAILED() {
        rabbitAdmin.deleteQueue(WORK_QUEUE);   // 바인딩도 같이 없어져 NO_ROUTE 로 반환된다
        try {
            long jobId = tx.execute(status -> enqueue("a"));

            Map<String, Object> row = row(jobId);
            assertThat(row.get("publish_status")).isEqualTo("PUBLISH_FAILED");
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("queued_at")).isNull();
        } finally {
            rabbitAdmin.initialize();
        }
    }

    @Test
    void 브로커가_NACK하면_PUBLISH_FAILED() {
        // 가득 찬(max-length 0) reject-publish 큐로 바꾸면 브로커가 발행을 NACK 한다
        rabbitAdmin.deleteQueue(WORK_QUEUE);
        Queue full = QueueBuilder.durable(WORK_QUEUE).maxLength(0).overflow(QueueBuilder.Overflow.rejectPublish).build();
        rabbitAdmin.declareQueue(full);
        Binding binding = BindingBuilder.bind(full).to(new DirectExchange(AiJobRoute.EXCHANGE)).with(WORK_QUEUE);
        rabbitAdmin.declareBinding(binding);
        try {
            long jobId = tx.execute(status -> enqueue("a"));

            Map<String, Object> row = row(jobId);
            assertThat(row.get("publish_status")).isEqualTo("PUBLISH_FAILED");
            assertThat(row.get("status")).isEqualTo("PENDING");
        } finally {
            rabbitAdmin.deleteQueue(WORK_QUEUE);
            rabbitAdmin.initialize();
        }
    }

    @Test
    void 큐와_exchange는_durable이고_dead_letter_경로가_맞다() throws Exception {
        JsonNode exchange = RabbitTestBroker.managementApi("exchanges/%2F/" + AiJobRoute.EXCHANGE);
        assertThat(exchange.path("type").stringValue()).isEqualTo("direct");
        assertThat(exchange.path("durable").booleanValue()).isTrue();

        JsonNode work = RabbitTestBroker.queue(WORK_QUEUE);
        assertThat(work.path("durable").booleanValue()).isTrue();
        assertThat(work.path("arguments").path("x-dead-letter-exchange").stringValue()).isEqualTo(AiJobRoute.EXCHANGE);
        assertThat(work.path("arguments").path("x-dead-letter-routing-key").stringValue()).isEqualTo(DEAD_LETTER_QUEUE);

        JsonNode retry = RabbitTestBroker.queue(RETRY_QUEUE);
        assertThat(retry.path("durable").booleanValue()).isTrue();
        assertThat(retry.path("arguments").path("x-message-ttl").intValue()).isEqualTo(10_000);
        assertThat(retry.path("arguments").path("x-dead-letter-exchange").stringValue()).isEqualTo(AiJobRoute.EXCHANGE);
        assertThat(retry.path("arguments").path("x-dead-letter-routing-key").stringValue()).isEqualTo(WORK_QUEUE);

        // RabbitMQ 4 는 선언하지 않은 기본 큐 타입(x-queue-type: classic)도 arguments 에 보여 준다
        JsonNode dlq = RabbitTestBroker.queue(DEAD_LETTER_QUEUE);
        assertThat(dlq.path("durable").booleanValue()).isTrue();
        assertThat(dlq.path("arguments").propertyNames()).containsOnly("x-queue-type");

        JsonNode bindings = RabbitTestBroker.managementApi("exchanges/%2F/" + AiJobRoute.EXCHANGE + "/bindings/source");
        assertThat(bindings.valueStream().map(b -> b.path("destination").stringValue() + "<-" + b.path("routing_key").stringValue()))
                .containsExactlyInAnyOrder(WORK_QUEUE + "<-" + WORK_QUEUE, RETRY_QUEUE + "<-" + RETRY_QUEUE,
                        DEAD_LETTER_QUEUE + "<-" + DEAD_LETTER_QUEUE);
    }

    @Test
    void worker가_reject하면_DLQ로_간다() {
        rabbitTemplate.send(AiJobRoute.EXCHANGE, WORK_QUEUE, testMessage());

        // worker 처럼 받아서 requeue=false 로 거절한다(보낸 채널과 달라 바로 안 보일 수 있어 잠깐 기다린다)
        Boolean rejected = rabbitTemplate.execute(channel -> {
            for (int i = 0; i < 50; i++) {
                GetResponse response = channel.basicGet(WORK_QUEUE, false);
                if (response != null) {
                    channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
                    return true;
                }
                Thread.sleep(100);
            }
            return false;
        });

        assertThat(rejected).isTrue();
        assertThat(rabbitTemplate.receive(DEAD_LETTER_QUEUE, 5_000)).isNotNull();
        assertThat(messageCount(WORK_QUEUE)).isZero();
    }

    @Test
    void retry_큐로_보내면_TTL_뒤_작업_큐로_돌아온다() {
        rabbitTemplate.send(AiJobRoute.EXCHANGE, RETRY_QUEUE, testMessage());
        assertThat(rabbitTemplate.receive(WORK_QUEUE, 1_000)).isNull();

        Message back = rabbitTemplate.receive(WORK_QUEUE, 15_000);

        assertThat(back).isNotNull();
        assertThat(new String(back.getBody())).isEqualTo("{\"test\":true}");
    }

    // ---------- helpers ----------

    private long enqueue(String suffix) {
        long resourceId = 900_000_000L + (long) (Math.random() * 99_999_999);
        return enqueueService.enqueue(AiJobType.POLICY_INGEST, AiJobResourceType.POLICY_DOCUMENT, resourceId, null,
                keyPrefix + suffix).getId();
    }

    private Map<String, Object> row(long jobId) {
        return jdbcTemplate.queryForMap("SELECT * FROM ai_jobs WHERE id = ?", jobId);
    }

    private long messageCount(String queue) {
        return Objects.requireNonNull(rabbitAdmin.getQueueInfo(queue)).getMessageCount();
    }

    private static Message testMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message("{\"test\":true}".getBytes(), properties);
    }
}
