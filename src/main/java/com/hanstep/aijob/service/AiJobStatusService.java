package com.hanstep.aijob.service;

import com.hanstep.aijob.domain.AiJob;
import com.hanstep.aijob.dto.JobFailedRequest;
import com.hanstep.aijob.dto.JobStartedRequest;
import com.hanstep.aijob.repository.AiJobRepository;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ai_jobs 상태 전이는 여기서만 한다(INT-08, INT-10, 발행 결과). 시각은 모두 Spring 이 받은 시각(worker 시계와 섞지 않음).
 */
@Service
public class AiJobStatusService {

    private static final Logger log = LoggerFactory.getLogger(AiJobStatusService.class);

    /** ai_jobs.error_message 는 varchar(500)(utf8mb4 문자 수). AI 는 message[:500] 으로 자른다(코드포인트). */
    private static final int MAX_ERROR_MESSAGE = 500;

    private final AiJobRepository aiJobRepository;

    public AiJobStatusService(AiJobRepository aiJobRepository) {
        this.aiJobRepository = aiJobRepository;
    }

    /** PENDING·QUEUED → PROCESSING. 이미 시작·종료된 작업이면 409(AI 는 "이미 시작됨"으로 처리). worker 의 startedAt 은 로그에만. */
    @Transactional
    public void markStarted(Long jobId, JobStartedRequest request) {
        AiJob job = findForUpdate(jobId);
        if (!job.canStart()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "job already started or finished",
                    Map.of("jobId", jobId, "status", job.getStatus().name()));
        }
        job.start(Instant.now());
        log.info("ai job {} started by {} (worker startedAt={})", jobId, request.workerId(), request.startedAt());
    }

    /**
     * → FAILED. 항상 204: 같은 오류 재전송은 그대로 두고, 다른 오류(재전달 후 다시 실패)는 마지막 실패로 덮어쓴다.
     * 이미 COMPLETED 인 작업에 온 실패는 무시한다(AI job_failed 는 409 를 처리하지 않는다).
     */
    @Transactional
    public void markFailed(Long jobId, JobFailedRequest request) {
        validateMessageLength(request.message());
        AiJob job = findForUpdate(jobId);
        if (job.isFailedWith(request.errorCode(), request.message())) {
            return;
        }
        if (!job.canFail()) {
            log.warn("ai job {} already completed, ignoring failure {}", jobId, request.errorCode());
            return;
        }
        job.fail(request.errorCode(), request.message(), Instant.now());
        log.info("ai job {} failed: {} (retryable={})", jobId, request.errorCode(), request.retryable());
    }

    /**
     * 브로커 ACK 후 → PUBLISHED, queued_at, PENDING 이면 QUEUED.
     * 커밋이 끝난 뒤(AFTER_COMMIT)에 불리므로 새 트랜잭션에서 행을 잠근다. started 와 같은 잠금이라
     * worker 가 먼저 PROCESSING 으로 바꿨으면 상태를 되돌리지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(Long jobId) {
        aiJobRepository.findByIdForUpdate(jobId).ifPresentOrElse(job -> job.markPublished(Instant.now()),
                () -> log.warn("ai job {} not found when recording publish", jobId));
    }

    /** 발행 실패 → PUBLISH_FAILED. 작업 상태는 그대로(PENDING). markPublished 와 같이 새 트랜잭션. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublishFailed(Long jobId) {
        aiJobRepository.findByIdForUpdate(jobId).ifPresentOrElse(AiJob::markPublishFailed,
                () -> log.warn("ai job {} not found when recording publish failure", jobId));
    }

    // @Size 는 UTF-16 길이라 이모지 등이 섞이면 AI 가 잘라 보낸 500자도 넘는다. 코드포인트로 센다.
    private static void validateMessageLength(String message) {
        if (message.codePointCount(0, message.length()) > MAX_ERROR_MESSAGE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, null, Map.of("fields", List.of(
                    Map.of("field", "message", "reason", "must be at most " + MAX_ERROR_MESSAGE + " characters"))));
        }
    }

    private AiJob findForUpdate(Long jobId) {
        return aiJobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("jobId", jobId)));
    }
}
