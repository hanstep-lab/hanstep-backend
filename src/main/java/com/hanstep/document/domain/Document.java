package com.hanstep.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * documents 테이블. 업로드 문서(외국인등록증) 메타. OCR 원 응답은 저장하지 않고 항목 값은 document_fields 에 둔다.
 * 시각 칸은 UTC.
 */
@Entity
@Table(name = "documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false,
            columnDefinition = "enum('ARC','DRIVER_LICENSE','CERTIFICATE','INSURANCE_PDF','OTHER')")
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, columnDefinition = "enum('FRONT','BACK','SINGLE')")
    private DocumentSide side;

    /** R2 hanstep-arc-private 버킷 키. 원본 삭제 후 NULL. */
    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false, columnDefinition = "int unsigned")
    private Integer fileSizeBytes;

    @Column(name = "sha256", nullable = false, columnDefinition = "char(64)")
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "enum('UPLOADED','OCR_QUEUED','OCR_PROCESSING',"
            + "'PENDING_CONFIRMATION','CONFIRMED','FAILED')")
    private DocumentStatus status;

    @Column(name = "ocr_model_version", length = 60)
    private String ocrModelVersion;

    @Column(name = "ocr_latency_ms", columnDefinition = "int unsigned")
    private Integer ocrLatencyMs;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "original_deleted_at")
    private Instant originalDeletedAt;

    @Column(name = "retention_until", nullable = false)
    private Instant retentionUntil;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * 외국인등록증 업로드(#73). 면 하나 = 문서 1건이고 UPLOADED 로 시작한다. object_key 는 R2 업로드 전에 만든
     * arc/{side}/{UUID}.{ext}(사용자·문서 id 를 넣지 않음, 지수 합의 11/16).
     * 진행·실패 상태는 OCR 작업(ai_jobs)에서 읽으므로 OCR_QUEUED·OCR_PROCESSING·FAILED 는 쓰지 않는다.
     */
    public static Document uploadedArc(Long userId, DocumentSide side, String objectKey, String contentType,
                                       int fileSizeBytes, String sha256, Instant retentionUntil) {
        Document doc = new Document();
        doc.userId = userId;
        doc.documentType = DocumentType.ARC;
        doc.side = side;
        doc.objectKey = objectKey;
        doc.contentType = contentType;
        doc.fileSizeBytes = fileSizeBytes;
        doc.sha256 = sha256;
        doc.status = DocumentStatus.UPLOADED;
        doc.retentionUntil = retentionUntil;
        return doc;
    }

    /**
     * 사용자 확정(DOC-04). PENDING_CONFIRMATION → CONFIRMED. 원본 키를 지우고(object_key NULL, original_deleted_at)
     * 지울 R2 키를 돌려준다(이미 지워졌으면 null).
     */
    public String confirm(Instant at) {
        if (status != DocumentStatus.PENDING_CONFIRMATION) {
            throw new IllegalStateException("document " + id + " cannot be confirmed from " + status);
        }
        String original = objectKey;
        this.status = DocumentStatus.CONFIRMED;
        this.confirmedAt = at;
        this.objectKey = null;
        this.originalDeletedAt = at;
        return original;
    }

    /** 원본 보관 기한(retention_until)이 지났는데 원본이 남아 있는가(#162). 확정된 문서는 object_key 가 NULL 이라 아니다. */
    public boolean originalExpired(Instant now) {
        return objectKey != null && retentionUntil.isBefore(now);
    }

    /** 보관 기한이 지나 R2 원본을 지운 뒤 기록한다(#162). 문서 상태는 바꾸지 않는다. */
    public void markOriginalExpired(Instant at) {
        this.objectKey = null;
        this.originalDeletedAt = at;
    }

    /**
     * OCR 결과를 받을 수 있는 상태인가. 업로드 직후(UPLOADED)도 받는다: 발행 confirm 기록보다 worker 결과가 먼저 올 수 있다.
     */
    public boolean canRecordOcrResult() {
        return status == DocumentStatus.UPLOADED || status == DocumentStatus.OCR_QUEUED
                || status == DocumentStatus.OCR_PROCESSING;
    }

    /** OCR 결과 반영 → PENDING_CONFIRMATION(사용자 확인 대기). 항목 값은 DocumentField 로 따로 넣는다. */
    public void recordOcrResult(String modelVersion, int latencyMs) {
        if (!canRecordOcrResult()) {
            throw new IllegalStateException("document " + id + " cannot record OCR result from " + status);
        }
        this.status = DocumentStatus.PENDING_CONFIRMATION;
        this.ocrModelVersion = modelVersion;
        this.ocrLatencyMs = latencyMs;
    }
}
