package com.hanstep.aijob.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * U2(b): 발행 대기 작업 주기 스캔(기본 1분, 마지막 변경 후 30초 지난 작업). OCR·POLICY_INGEST 모두.
 * hanstep.ai-jobs.republish.enabled=false 면 만들지 않는다(테스트는 build.gradle 에서 끈다: 로컬 공용 브로커로 보내지 않게).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "hanstep.ai-jobs.republish.enabled", havingValue = "true", matchIfMissing = true)
public class AiJobRepublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiJobRepublishScheduler.class);

    private final AiJobRepublishService republishService;
    private final int minIdleSeconds;
    private final int batchSize;

    public AiJobRepublishScheduler(AiJobRepublishService republishService,
                                   @Value("${hanstep.ai-jobs.republish.min-idle-seconds:30}") int minIdleSeconds,
                                   @Value("${hanstep.ai-jobs.republish.batch-size:100}") int batchSize) {
        this.republishService = republishService;
        this.minIdleSeconds = minIdleSeconds;
        this.batchSize = batchSize;
    }

    /** 한 번에 batchSize 개까지. 하나가 실패해도 나머지는 계속한다. */
    @Scheduled(fixedDelayString = "${hanstep.ai-jobs.republish.interval-ms:60000}",
            initialDelayString = "${hanstep.ai-jobs.republish.initial-delay-ms:60000}")
    public void republishStale() {
        List<Long> candidates = republishService.findStale(minIdleSeconds, batchSize);
        if (candidates.isEmpty()) {
            return;
        }
        int requested = 0;
        for (Long jobId : candidates) {
            try {
                if (republishService.republish(jobId, minIdleSeconds)) {
                    requested++;
                }
            } catch (RuntimeException e) {
                log.error("ai job {} republish failed", jobId, e);
            }
        }
        log.info("ai job republish scan: {} candidates, {} requested", candidates.size(), requested);
    }
}
