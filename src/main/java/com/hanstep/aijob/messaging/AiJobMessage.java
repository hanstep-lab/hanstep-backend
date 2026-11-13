package com.hanstep.aijob.messaging;

import com.hanstep.aijob.domain.AiJob;
import com.hanstep.aijob.domain.AiJobResourceType;
import com.hanstep.aijob.domain.AiJobType;

/**
 * RabbitMQ 작업 메시지 본문(JSON). worker 는 jobId 로 ai_jobs 를, resourceType·resourceId 로 대상을 다시 조회한다.
 * 개인정보·문서 본문·URL·계정 정보는 넣지 않는다. 필드를 바꾸면 schemaVersion 을 올린다.
 */
public record AiJobMessage(
        int schemaVersion,
        long jobId,
        AiJobType jobType,
        AiJobResourceType resourceType,
        Long resourceId,
        String requestId
) {
    public static final int SCHEMA_VERSION = 1;

    /** requestId 는 작업을 만든 요청의 추적 ID(ai_jobs.correlation_id). */
    public static AiJobMessage of(AiJob job) {
        return new AiJobMessage(SCHEMA_VERSION, job.getId(), job.getJobType(), job.getResourceType(),
                job.getResourceId(), job.getCorrelationId());
    }
}
