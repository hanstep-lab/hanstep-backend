package com.hanstep.document.dto;

import com.hanstep.aijob.domain.AiJob;
import com.hanstep.aijob.domain.AiJobStatus;
import com.hanstep.aijob.domain.PublishStatus;
import com.hanstep.document.domain.Document;
import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.domain.DocumentStatus;
import com.hanstep.document.domain.DocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 등록증 문서(DOC-01 업로드 응답). 원본 위치(object_key)·sha256 은 주지 않는다. */
@Schema(description = "외국인등록증 문서")
public record DocumentResponse(
        @Schema(description = "documents.id", example = "42")
        Long documentId,
        @Schema(description = "문서 종류. 등록증은 ARC", example = "ARC")
        DocumentType documentType,
        @Schema(description = "면. 앞면만 받는다", example = "FRONT")
        DocumentSide side,
        @Schema(description = "문서 상태. UPLOADED(OCR 대기·처리 중·실패는 ocrJob 으로 판단) → "
                + "PENDING_CONFIRMATION(OCR 결과 확인 대기) → CONFIRMED(확정, 원본 삭제)", example = "UPLOADED")
        DocumentStatus status,
        @Schema(description = "가장 최근 OCR 작업. 진행·실패 상태는 여기서 본다", nullable = true)
        OcrJob ocrJob,
        @Schema(description = "업로드 시각", example = "2026-11-16T16:40:00+09:00")
        OffsetDateTime uploadedAt,
        @Schema(description = "확정 시각", nullable = true, example = "2026-11-16T16:42:10+09:00")
        OffsetDateTime confirmedAt
) {

    @Schema(name = "DocumentOcrJob", description = "OCR 작업(ai_jobs)")
    public record OcrJob(
            @Schema(description = "ai_jobs.id", example = "101")
            Long jobId,
            @Schema(description = "작업 상태. PENDING(아직 큐에 못 들어감) · QUEUED · PROCESSING · COMPLETED · FAILED",
                    example = "QUEUED")
            AiJobStatus status,
            @Schema(description = "발행 상태. PENDING 이면서 NOT_PUBLISHED·PUBLISH_FAILED 면 발행 대기(자동·수동 재발행 대상)",
                    example = "PUBLISHED")
            PublishStatus publishStatus,
            @Schema(description = "실패 코드(FAILED 일 때)", nullable = true, example = "OCR_FAILED")
            String errorCode
    ) {

        public static OcrJob of(AiJob job) {
            return job == null ? null
                    : new OcrJob(job.getId(), job.getStatus(), job.getPublishStatus(), job.getErrorCode());
        }
    }

    public static DocumentResponse of(Document doc, AiJob ocrJob) {
        return new DocumentResponse(doc.getId(), doc.getDocumentType(), doc.getSide(), doc.getStatus(),
                OcrJob.of(ocrJob), kst(doc.getCreatedAt()), kst(doc.getConfirmedAt()));
    }

    private static OffsetDateTime kst(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.ofHours(9));
    }
}
