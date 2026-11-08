package com.hanstep.mydata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ingestion_batches 테이블. MYD-02 요청 1건 = 배치 1개(기관이 여러 개라 connection_id 는 비워 둠).
 * (user_id, request_key) UNIQUE 가 Idempotency-Key 멱등을 맡음. 시각 칸은 UTC.
 */
@Entity
@Table(name = "ingestion_batches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, columnDefinition = "enum('MYDATA','MOCK','USER_INPUT')")
    private SourceType sourceType;

    @Column(name = "is_mock", nullable = false)
    private boolean mock;

    @Column(name = "request_key", nullable = false, length = 100)
    private String requestKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "enum('PENDING','SUCCEEDED','PARTIAL','FAILED')")
    private BatchStatus status;

    @Column(name = "coverage_from")
    private LocalDate coverageFrom;

    @Column(name = "coverage_to")
    private LocalDate coverageTo;

    @Column(name = "record_count", columnDefinition = "int unsigned")
    private Integer recordCount;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 처리 시작 때 PENDING 으로 먼저 넣음. */
    public static IngestionBatch startMock(Long userId, String requestKey, Instant startedAt) {
        IngestionBatch batch = new IngestionBatch();
        batch.userId = userId;
        batch.sourceType = SourceType.MOCK;
        batch.mock = true;
        batch.requestKey = requestKey;
        batch.status = BatchStatus.PENDING;
        batch.startedAt = startedAt;
        return batch;
    }

    public void succeed(LocalDate coverageFrom, LocalDate coverageTo, int recordCount, Instant finishedAt) {
        this.status = BatchStatus.SUCCEEDED;
        this.coverageFrom = coverageFrom;
        this.coverageTo = coverageTo;
        this.recordCount = recordCount;
        this.finishedAt = finishedAt;
    }

    public void fail(String errorCode, Instant finishedAt) {
        this.status = BatchStatus.FAILED;
        this.errorCode = errorCode;
        this.finishedAt = finishedAt;
    }
}
