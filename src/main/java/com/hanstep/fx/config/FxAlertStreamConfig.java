package com.hanstep.fx.config;

import com.hanstep.fx.service.FxAlertStreamService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 목표 환율 도달 스트림의 실행기. 요청 스레드는 SseEmitter 를 돌려주고 바로 풀려나고(CHT-04 와 같은 방식),
 * 연결 시 보충·도달 이벤트·ping 전송은 fxAlertStreamExecutor(가상 스레드)에서 한다. 판정 스레드(수집기·다시 판정)도 붙잡지 않는다.
 * ping 은 @Scheduled 대신 전용 스레드로 돌린다(스케줄링은 수집기 등을 켤 때만 켜지고, 공용 스케줄러를 쓰지 않게).
 */
@Configuration
public class FxAlertStreamConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService fxAlertStreamExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("fx-alert-stream-", 0).factory());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService fxAlertStreamPinger(FxAlertStreamService streamService,
                                                        FxAlertStreamProperties properties) {
        ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "fx-alert-stream-ping");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMs = properties.pingInterval().toMillis();
        pinger.scheduleWithFixedDelay(streamService::pingAll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        return pinger;
    }
}
