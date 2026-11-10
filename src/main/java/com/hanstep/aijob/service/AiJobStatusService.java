package com.hanstep.aijob.service;

import com.hanstep.aijob.domain.AiJob;
import com.hanstep.aijob.dto.JobFailedRequest;
import com.hanstep.aijob.dto.JobStartedRequest;
import com.hanstep.aijob.repository.AiJobRepository;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ai_jobs 상태 전이는 여기서만 한다(INT-08, INT-10). */
@Service
public class AiJobStatusService {

    private static final Logger log = LoggerFactory.getLogger(AiJobStatusService.class);

    private final AiJobRepository aiJobRepository;

    public AiJobStatusService(AiJobRepository aiJobRepository) {
        this.aiJobRepository = aiJobRepository;
    }

    /** PENDING·QUEUED → PROCESSING. 이미 시작·종료된 작업이면 409(AI 는 "이미 시작됨"으로 처리). */
    @Transactional
    public void markStarted(Long jobId, JobStartedRequest request) {
        AiJob job = findForUpdate(jobId);
        if (!job.canStart()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "job already started or finished",
                    Map.of("jobId", jobId, "status", job.getStatus().name()));
        }
        job.start(request.startedAt().toInstant());
        log.info("ai job {} started by {}", jobId, request.workerId());
    }

    /** → FAILED. 같은 오류 재전송은 그대로 성공, 이미 끝난 작업에 다른 내용이 오면 409. */
    @Transactional
    public void markFailed(Long jobId, JobFailedRequest request) {
        AiJob job = findForUpdate(jobId);
        if (job.isFailedWith(request.errorCode(), request.message())) {
            return;
        }
        if (!job.canFail()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "job already finished",
                    Map.of("jobId", jobId, "status", job.getStatus().name()));
        }
        job.fail(request.errorCode(), request.message(), Instant.now());
        log.info("ai job {} failed: {} (retryable={})", jobId, request.errorCode(), request.retryable());
    }

    private AiJob findForUpdate(Long jobId) {
        return aiJobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("jobId", jobId)));
    }
}
