package com.hanstep.aijob.messaging;

import com.hanstep.aijob.service.AiJobStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 작업을 저장한 트랜잭션이 커밋된 뒤에만 발행한다. 롤백이면 이벤트가 버려진다(fallbackExecution 없음).
 * 커밋한 스레드에서 돌기 때문에 요청 응답은 confirm(최대 hanstep.ai-jobs.confirm-timeout)을 기다린 뒤 나간다.
 *
 * <p>여기서 난 예외는 모두 잡아 로그와 publish_status 로만 남긴다. 요청의 저장은 이미 커밋됐으므로
 * 발행·상태 기록 실패가 그 요청의 응답을 실패로 바꾸면 안 된다.
 * 커밋 직후 프로세스가 죽으면 작업이 NOT_PUBLISHED 로 남는다(미발행 재발행은 아직 없음).
 */
@Component
public class AiJobPublishListener {

    private static final Logger log = LoggerFactory.getLogger(AiJobPublishListener.class);

    private final AiJobPublisher publisher;
    private final AiJobStatusService statusService;

    public AiJobPublishListener(AiJobPublisher publisher, AiJobStatusService statusService) {
        this.publisher = publisher;
        this.statusService = statusService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(AiJobCreatedEvent event) {
        AiJobMessage message = event.message();
        long jobId = message.jobId();
        try {
            publisher.publish(message);
        } catch (AiJobPublishException e) {
            log.warn("ai job {} ({}) publish failed: {}", jobId, message.jobType(), e.getMessage());
            recordFailure(jobId);
            return;
        } catch (RuntimeException e) {
            log.error("ai job {} ({}) publish failed unexpectedly", jobId, message.jobType(), e);
            recordFailure(jobId);
            return;
        }
        try {
            statusService.markPublished(jobId);
            log.info("ai job {} ({}) published", jobId, message.jobType());
        } catch (RuntimeException e) {
            // 브로커에는 들어갔고 상태 기록만 실패했다(NOT_PUBLISHED 로 남음)
            log.error("ai job {} published but publish status was not recorded", jobId, e);
        }
    }

    private void recordFailure(long jobId) {
        try {
            statusService.markPublishFailed(jobId);
        } catch (RuntimeException e) {
            log.error("ai job {} publish failure was not recorded", jobId, e);
        }
    }
}
