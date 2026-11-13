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
