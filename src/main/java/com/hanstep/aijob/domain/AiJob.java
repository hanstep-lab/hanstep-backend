package com.hanstep.aijob.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ai_jobs 테이블. RabbitMQ 비동기 작업의 단일 진실(Spring 만 갱신). id = MQ jobId. 시각 칸은 UTC.
 * 상태 전이는 이 엔티티 메서드로만 한다.
 */
@Entity
@Table(name = "ai_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 정책 동기화 작업은 NULL. */
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, columnDefinition = "enum('OCR','POLICY_INGEST','DOCUMENT_ANALYSIS')")
    private AiJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false,
            columnDefinition = "enum('DOCUMENT','POLICY_DOCUMENT','POLICY_SOURCE')")
    private AiJobResourceType resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false,
            columnDefinition = "enum('PENDING','QUEUED','PROCESSING','COMPLETED','FAILED')")
    private AiJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "publish_status", nullable = false,
            columnDefinition = "enum('NOT_PUBLISHED','PUBLISHED','PUBLISH_FAILED')")
    private PublishStatus publishStatus;

    @Column(name = "retry_count", nullable = false, columnDefinition = "tinyint unsigned")
    private Integer retryCount;

    @Column(name = "max_retries", nullable = false, columnDefinition = "tinyint unsigned")
    private Integer maxRetries;

    @Column(name = "result_reference", length = 100)
    private String resultReference;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    /** 오류 요약(개인정보 금지). */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /** 새 작업. PENDING·NOT_PUBLISHED 로 시작하고, 커밋 후 발행 결과에 따라 발행 상태를 바꾼다. */
    public static AiJob create(Long userId, AiJobType jobType, AiJobResourceType resourceType, Long resourceId,
                               String idempotencyKey, String correlationId, int maxRetries) {
        AiJob job = new AiJob();
        job.userId = userId;
        job.jobType = jobType;
        job.resourceType = resourceType;
        job.resourceId = resourceId;
        job.idempotencyKey = idempotencyKey;
        job.correlationId = correlationId;
        job.status = AiJobStatus.PENDING;
        job.publishStatus = PublishStatus.NOT_PUBLISHED;
        job.retryCount = 0;
        job.maxRetries = maxRetries;
        return job;
    }

    /**
     * 브로커가 받았다(ACK, 라우팅됨). PENDING 일 때만 QUEUED 로 바꾼다.
     * confirm 보다 worker 의 started 가 먼저 와서 PROCESSING·종료 상태가 됐으면 상태는 그대로 둔다.
     */
    public void markPublished(Instant queuedAt) {
        this.publishStatus = PublishStatus.PUBLISHED;
        if (this.queuedAt == null) {
            this.queuedAt = queuedAt;
        }
        if (status == AiJobStatus.PENDING) {
            this.status = AiJobStatus.QUEUED;
        }
    }

    /** 발행 실패(NACK·반환·연결 오류·confirm 시간 초과). 작업 상태는 바꾸지 않는다. 이미 PUBLISHED 면 그대로. */
    public void markPublishFailed() {
        if (publishStatus != PublishStatus.PUBLISHED) {
            this.publishStatus = PublishStatus.PUBLISH_FAILED;
        }
    }

    /** PENDING·QUEUED 일 때만 시작할 수 있다. */
    public boolean canStart() {
        return status == AiJobStatus.PENDING || status == AiJobStatus.QUEUED;
    }

    public void start(Instant startedAt) {
        if (!canStart()) {
            throw new IllegalStateException("job " + id + " cannot start from " + status);
        }
        this.status = AiJobStatus.PROCESSING;
        this.startedAt = startedAt;
    }

    /** 이미 같은 오류로 실패 처리된 상태인가(재전송 판단). */
    public boolean isFailedWith(String errorCode, String errorMessage) {
        return status == AiJobStatus.FAILED
                && Objects.equals(this.errorCode, errorCode)
                && Objects.equals(this.errorMessage, errorMessage);
    }

    /** 완료된 작업은 실패로 되돌리지 않는다. FAILED 에 다시 오면 마지막 실패로 덮어쓴다(재전달 후 다른 오류). */
    public boolean canFail() {
        return status != AiJobStatus.COMPLETED;
    }

    public void fail(String errorCode, String errorMessage, Instant completedAt) {
        if (!canFail()) {
            throw new IllegalStateException("job " + id + " cannot fail from " + status);
        }
        this.status = AiJobStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.completedAt = completedAt;
    }
}
