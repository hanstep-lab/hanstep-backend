package com.hanstep.aijob.service;

import com.hanstep.aijob.domain.AiJob;
import com.hanstep.aijob.messaging.AiJobCreatedEvent;
import com.hanstep.aijob.messaging.AiJobMessage;
import com.hanstep.aijob.messaging.AiJobRoute;
import com.hanstep.aijob.repository.AiJobRepository;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * U2: 발행 대기 작업(status=PENDING, publish_status NOT_PUBLISHED·PUBLISH_FAILED) 재발행. 새 작업을 만들지 않고 같은 jobId 로
 * 다시 보낸다. 보내기는 기존 경로 그대로(커밋 후 AiJobPublishListener → confirm 결과로 PUBLISHED·PUBLISH_FAILED).
 * 동시에 두 번 보내질 수 있으나 worker 가 started 409(JOB_ALREADY_PROCESSING·JOB_ALREADY_FINISHED)로 거른다.
 * retry_count 는 쓰지 않는다(worker 재시도 횟수와 섞이지 않게).
 */
@Service
public class AiJobRepublishService {

    private static final Logger log = LoggerFactory.getLogger(AiJobRepublishService.class);

    /** 발행 경로가 있는 작업 종류(DOCUMENT_ANALYSIS 처럼 경로가 없는 작업은 보내지 않는다). */
    private static final String ROUTED_TYPES = Arrays.stream(AiJobRoute.values())
            .map(route -> "'" + route.jobType().name() + "'").collect(Collectors.joining(", "));

    private final AiJobRepository aiJobRepository;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;

    public AiJobRepublishService(AiJobRepository aiJobRepository, ApplicationEventPublisher events,
                                 JdbcTemplate jdbc) {
        this.aiJobRepository = aiJobRepository;
        this.events = events;
        this.jdbc = jdbc;
    }

    /**
     * 주기 스캔 후보: 발행 대기이면서 마지막 변경 후 minIdleSeconds 가 지난 작업(방금 만든 작업의 커밋 후 발행과 겹치지 않게).
     * 시각 비교는 DB 시계로 한다(updated_at 도 DB 가 넣음).
     */
    @Transactional(readOnly = true)
    public List<Long> findStale(int minIdleSeconds, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM ai_jobs
                WHERE status = 'PENDING' AND publish_status IN ('NOT_PUBLISHED', 'PUBLISH_FAILED')
                  AND job_type IN (%s)
                  AND updated_at < CURRENT_TIMESTAMP(3) - INTERVAL ? SECOND
                ORDER BY id
                LIMIT ?""".formatted(ROUTED_TYPES), Long.class, minIdleSeconds, limit);
    }

    /**
     * 행을 잠그고 아직 발행 대기인지 다시 본 뒤, updated_at 을 지금으로 바꾸고(다른 스캔이 30초 안에 또 집지 않게)
     * 커밋 후 보낸다. minIdleSeconds 가 있으면 그 시간이 지난 작업만(스캔), null 이면 바로(사용자 다시 시도).
     *
     * @return 보내기로 했으면 true. 이미 발행됐거나 시작·종료된 작업, 경로 없는 작업이면 false
     */
    @Transactional
    public boolean republish(Long jobId, Integer minIdleSeconds) {
        AiJob job = aiJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isAwaitingPublish() || !AiJobRoute.supports(job.getJobType())) {
            return false;
        }
        if (minIdleSeconds != null && !idleFor(jobId, minIdleSeconds)) {
            return false;
        }
        jdbc.update("UPDATE ai_jobs SET updated_at = CURRENT_TIMESTAMP(3) WHERE id = ?", jobId);
        events.publishEvent(new AiJobCreatedEvent(AiJobMessage.of(job)));
        log.info("ai job {} ({}) republish requested (was {})", jobId, job.getJobType(), job.getPublishStatus());
        return true;
    }

    private boolean idleFor(Long jobId, int seconds) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_jobs WHERE id = ? AND updated_at < CURRENT_TIMESTAMP(3) - INTERVAL ? SECOND",
                Integer.class, jobId, seconds);
        return count != null && count > 0;
    }
}
