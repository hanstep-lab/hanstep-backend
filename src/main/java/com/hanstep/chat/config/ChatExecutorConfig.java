package com.hanstep.chat.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CHT-04 스트림 중계 실행기(chatStreamExecutor). 요청 스레드는 SseEmitter 를 돌려주고 바로 풀려나고,
 * AI 스트림 읽기는 이 실행기에서 한다. 기본은 virtual(가상 스레드)이고 platform(고정 풀)은 설정으로 고를 수 있다.
 * 근거는 ChatStreamConcurrencyPerfTest(고정 풀은 크기를 넘는 동시 요청을 큐에 세워 첫 delta 가 늦어짐).
 */
@Configuration
public class ChatExecutorConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService chatStreamExecutor(ChatProperties chat) {
        return create(chat.streamExecutor(), chat.streamPoolSize());
    }

    /** platform: 고정 풀(큐 제한 없음), virtual: 작업마다 가상 스레드. */
    public static ExecutorService create(ChatProperties.StreamExecutor type, int poolSize) {
        if (type == ChatProperties.StreamExecutor.VIRTUAL) {
            return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-stream-v-", 0).factory());
        }
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread thread = new Thread(r, "chat-stream-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(poolSize, poolSize, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                factory);
    }
}
