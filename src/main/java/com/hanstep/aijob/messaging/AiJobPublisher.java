package com.hanstep.aijob.messaging;

import com.hanstep.aijob.messaging.AiJobPublishException.Reason;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 작업 메시지를 보내고 브로커 확인까지 기다린다. 큐에 들어갔다고 볼 수 있는 경우는 ACK 이면서 반환되지 않았을 때뿐이다.
 * publisher confirm(correlated)·returns·mandatory 는 application.yaml 의 spring.rabbitmq 설정을 쓴다.
 */
@Component
public class AiJobPublisher {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public AiJobPublisher(RabbitTemplate rabbitTemplate,
                          @Value("${hanstep.ai-jobs.confirm-timeout:5s}") Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    /** 실패(NACK·반환·연결 오류·confirm 시간 초과)면 AiJobPublishException. */
    public void publish(AiJobMessage payload) {
        AiJobRoute route = AiJobRoute.forType(payload.jobType());
        Message message = MessageBuilder.withBody(MAPPER.writeValueAsBytes(payload))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(String.valueOf(payload.jobId()))
                .build();
        // 이 발행의 confirm·반환을 묶는 값. 같은 작업을 다시 보내도 겹치지 않게 매번 새로 만든다
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbitTemplate.send(AiJobRoute.EXCHANGE, route.routingKey(), message, correlation);
        } catch (AmqpException e) {
            throw new AiJobPublishException(Reason.SEND_FAILED, e.getClass().getSimpleName(), e);
        }

        Confirm confirm = awaitConfirm(correlation);
        // 라우팅되지 않은 메시지는 브로커가 돌려준 뒤 ACK 도 보낸다. ACK 만 보고 성공으로 치지 않는다
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            throw new AiJobPublishException(Reason.RETURNED,
                    returned.getReplyCode() + " " + returned.getReplyText(), null);
        }
        if (!confirm.ack()) {
            throw new AiJobPublishException(Reason.NACK, confirm.reason(), null);
        }
    }

    private Confirm awaitConfirm(CorrelationData correlation) {
        try {
            return correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AiJobPublishException(Reason.CONFIRM_TIMEOUT, confirmTimeout.toString(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiJobPublishException(Reason.INTERRUPTED, null, e);
        } catch (ExecutionException e) {
            throw new AiJobPublishException(Reason.SEND_FAILED, e.getCause().getClass().getSimpleName(), e);
        }
    }
}
