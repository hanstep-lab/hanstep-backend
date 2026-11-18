package com.hanstep.fx.service;

import com.hanstep.common.time.KstDates;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 환율 1시간 주기 수집(#119). hanstep.fx-collector.enabled=true 일 때만 만든다(.env 의 FX_COLLECTOR_ENABLED, 기본 false).
 * 꺼져 있어도 백필(POST /internal/v1/fx/backfill)은 쓸 수 있다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "hanstep.fx-collector.enabled", havingValue = "true")
public class FxCollectScheduler {

    private static final Logger log = LoggerFactory.getLogger(FxCollectScheduler.class);

    private final FxCollectService collectService;

    public FxCollectScheduler(FxCollectService collectService) {
        this.collectService = collectService;
    }

    /** 원천별 실패는 서비스가 WARN 으로 남긴다. 그 밖의 예외도 위로 던지지 않는다(다음 주기에 다시 한다). */
    @Scheduled(fixedDelayString = "${hanstep.fx-collector.interval-ms:3600000}",
            initialDelayString = "${hanstep.fx-collector.initial-delay-ms:60000}")
    public void collect() {
        try {
            collectService.collectLatest(LocalDate.now(KstDates.KST));
        } catch (RuntimeException e) {
            log.warn("fx collect run failed: reason={}", e.getClass().getSimpleName());
        }
    }
}
