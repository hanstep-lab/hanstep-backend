package com.hanstep.document.service;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 등록증 원본 보관 기한 만료 삭제 주기 실행(#162, 기본 1시간). hanstep.arc-original-expiry.enabled=false 면 만들지 않는다
 * (.env 의 ARC_ORIGINAL_EXPIRY_ENABLED, 기본 켬). 테스트는 build.gradle 에서 끈다(공용 DB 의 문서를 건드리지 않게).
 * 로그에는 문서 id·건수·예외 클래스 이름만 남긴다(object_key·항목 값·등록번호 없음).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "hanstep.arc-original-expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ArcOriginalExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArcOriginalExpiryScheduler.class);

    private final ArcOriginalExpiryService expiryService;
    private final int batchSize;

    public ArcOriginalExpiryScheduler(ArcOriginalExpiryService expiryService,
                                      @Value("${hanstep.arc-original-expiry.batch-size:100}") int batchSize) {
        this.expiryService = expiryService;
        this.batchSize = batchSize;
    }

    /** 대상 조회가 실패해도 위로 던지지 않는다(다음 주기에 다시 한다). */
    @Scheduled(fixedDelayString = "${hanstep.arc-original-expiry.interval-ms:3600000}",
            initialDelayString = "${hanstep.arc-original-expiry.initial-delay-ms:60000}")
    public void deleteExpired() {
        try {
            run(Instant.now());
        } catch (RuntimeException e) {
            log.warn("arc original expiry run failed: reason={}", e.getClass().getSimpleName());
        }
    }

    /** 한 번에 batchSize 건까지(남은 문서는 다음 주기). 문서마다 트랜잭션을 따로 쓰고, 한 건이 실패해도 나머지는 계속한다. */
    void run(Instant now) {
        List<Long> expired = expiryService.findExpired(now, batchSize);
        if (expired.isEmpty()) {
            return;
        }
        int deleted = 0;
        int failed = 0;
        for (Long documentId : expired) {
            try {
                if (expiryService.deleteOriginal(documentId, now)) {
                    deleted++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("arc original expiry failed: documentId={}, reason={}", documentId,
                        e.getClass().getSimpleName());
            }
        }
        log.info("arc original expiry: expired={}, deleted={}, failed={}", expired.size(), deleted, failed);
    }
}
