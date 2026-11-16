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
 *   <li>{routingKey}.retry: worker 가 다시 시도할 메시지를 메시지 expiration(대기 ms)을 붙여 여기로 보내면, 만료된 뒤
 *       작업 큐로 돌아간다. 큐 TTL 은 두지 않는다(대기 5000·15000·45000ms 는 worker 가 정한다). 자동 재시도가 아니며
 *       횟수 제한도 worker 가 한다. 만료는 큐 맨 앞 메시지부터 확인하므로, 긴 대기 메시지 뒤의 짧은 대기 메시지는
 *       앞 메시지가 나갈 때까지 기다린다</li>
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
            Queue retry = QueueBuilder.durable(route.retryQueue())
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
