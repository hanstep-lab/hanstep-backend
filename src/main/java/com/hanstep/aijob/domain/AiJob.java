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
