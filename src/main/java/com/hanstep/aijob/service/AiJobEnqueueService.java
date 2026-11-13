package com.hanstep.aijob.service;

import com.hanstep.aijob.domain.AiJob;
import com.hanstep.aijob.domain.AiJobResourceType;
import com.hanstep.aijob.domain.AiJobType;
import com.hanstep.aijob.messaging.AiJobCreatedEvent;
import com.hanstep.aijob.messaging.AiJobMessage;
import com.hanstep.aijob.repository.AiJobRepository;
import com.hanstep.common.web.RequestIdFilter;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 생성. 호출한 쪽 트랜잭션 안에서만 저장하고(대상 리소스와 함께 커밋·롤백), 커밋되면 AiJobPublishListener 가 발행한다.
 * (job_type, idempotency_key) UNIQUE 위반은 DataIntegrityViolationException 으로 호출한 쪽에 올린다.
 */
@Service
public class AiJobEnqueueService {

    static final int DEFAULT_MAX_RETRIES = 3;

    private final AiJobRepository aiJobRepository;
    private final ApplicationEventPublisher events;

    public AiJobEnqueueService(AiJobRepository aiJobRepository, ApplicationEventPublisher events) {
        this.aiJobRepository = aiJobRepository;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AiJob enqueue(AiJobType jobType, AiJobResourceType resourceType, Long resourceId, Long userId,
                         String idempotencyKey) {
        AiJob job = aiJobRepository.saveAndFlush(AiJob.create(userId, jobType, resourceType, resourceId,
                idempotencyKey, correlationId(), DEFAULT_MAX_RETRIES));
        events.publishEvent(new AiJobCreatedEvent(AiJobMessage.of(job)));
        return job;
    }

    // HTTP 요청 안이면 RequestIdFilter 값(최대 64자), 밖이면 같은 길이 형식으로 새로 만든다(correlation_id varchar(64))
    static String correlationId() {
        String current = RequestIdFilter.current();
        return current != null ? current : "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
