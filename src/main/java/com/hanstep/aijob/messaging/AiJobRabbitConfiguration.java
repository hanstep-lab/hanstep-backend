package com.hanstep.aijob.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 작업 큐 구성. 선언은 BE 가 한다(첫 연결 때 RabbitAdmin 이 선언). worker 는 같은 인자로 선언하거나 passive 로 확인한다
 * (인자가 다르면 PRECONDITION_FAILED). 작업 종류마다 direct exchange hanstep.ai 아래에 세 큐를 둔다.
 * <ul>
 *   <li>{routingKey}: 작업 큐. worker 가 reject(requeue=false)한 메시지는 {routingKey}.dlq 로 간다</li>
 *   <li>{routingKey}.retry: worker 가 다시 시도할 메시지를 여기로 보내면 TTL(10초, 임시값) 뒤 작업 큐로 돌아간다.
 *       자동 재시도가 아니며 횟수 제한은 worker 가 한다</li>
 *   <li>{routingKey}.dlq: 더 처리하지 않을 메시지</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AiJobRabbitConfiguration {
    @Bean
    public Declarables aiJobTopology() {
        DirectExchange exchange = new DirectExchange(AiJobRoute.EXCHANGE, true, false);
        List<Declarable> declarations = new ArrayList<>();
        declarations.add(exchange);
        for (AiJobRoute route : AiJobRoute.values()) {
            Queue work = QueueBuilder.durable(route.routingKey())
                    .deadLetterExchange(AiJobRoute.EXCHANGE)
                    .deadLetterRoutingKey(route.deadLetterQueue()).build();
            Queue retry = QueueBuilder.durable(route.retryQueue()).ttl(10_000)
                    .deadLetterExchange(AiJobRoute.EXCHANGE)
                    .deadLetterRoutingKey(route.routingKey()).build();
            Queue dlq = QueueBuilder.durable(route.deadLetterQueue()).build();
            declarations.add(work);
            declarations.add(retry);
            declarations.add(dlq);
            declarations.add(BindingBuilder.bind(work).to(exchange).with(route.routingKey()));
            declarations.add(BindingBuilder.bind(retry).to(exchange).with(route.retryQueue()));
            declarations.add(BindingBuilder.bind(dlq).to(exchange).with(route.deadLetterQueue()));
        }
        return new Declarables(declarations);
    }
}
