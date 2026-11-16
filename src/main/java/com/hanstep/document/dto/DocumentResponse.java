package com.hanstep.document.dto;

import com.hanstep.aijob.domain.AiJob;
import com.hanstep.aijob.domain.AiJobStatus;
import com.hanstep.aijob.domain.PublishStatus;
import com.hanstep.document.domain.ArcFieldKey;
import com.hanstep.document.domain.Document;
import com.hanstep.document.domain.DocumentField;
import com.hanstep.document.domain.DocumentFieldStatus;
import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.domain.DocumentStatus;
import com.hanstep.document.domain.DocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 등록증 문서(DOC-01 업로드·DOC-02 조회·DOC-03 수정·DOC-04 확정 응답). 원본 위치(object_key)·sha256 은 주지 않는다. */
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
        @Schema(description = "OCR 항목. OCR 결과가 오기 전에는 빈 배열. 확정 후에는 value 가 null 이고 status·confidence 만 남는다")
        List<Field> fields,
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

    @Schema(name = "DocumentField", description = "OCR 항목 하나")
    public record Field(
            @Schema(description = "항목 키. nameEn·nationality(ISO 3166-1 alpha-3)·birthDate(YYYY-MM-DD)·"
                    + "visaType(E-9 등)·stayExpiry(YYYY-MM-DD, 앞면에 없어 사용자가 입력)·registrationNo(마스킹값)",
                    example = "nationality")
            String fieldKey,
            @Schema(description = "지금 값. 사용자가 고쳤으면 고친 값, 아니면 OCR 값. OCR 이 못 읽었거나 확정 후면 null",
                    nullable = true, example = "PHL")
            String value,
            @Schema(description = "OCR 신뢰도 0~1. OCR 이 못 읽었으면 null", nullable = true, example = "0.9655")
            BigDecimal confidence,
            @Schema(description = "확인 필요 표시. 확정 전이고 신뢰도가 0.8 미만이거나 없으면 true", example = "false")
            boolean needsReview,
            @Schema(description = "EXTRACTED(OCR 값) · MISSING(못 읽음) · CONFIRMED(그대로 확정) · CORRECTED(사용자가 고침)",
                    example = "EXTRACTED")
            DocumentFieldStatus status,
            @Schema(description = "마스킹 값인가(registrationNo 는 true, 수정 불가)", example = "false")
            boolean isMasked
    ) {
    }

    /** 이 값 미만이면 확인 필요(DOC-02). */
    public static final BigDecimal REVIEW_THRESHOLD = new BigDecimal("0.8");

    public static DocumentResponse of(Document doc, AiJob ocrJob, List<DocumentField> fields) {
        boolean confirmed = doc.getStatus() == DocumentStatus.CONFIRMED;
        Map<String, DocumentField> byKey = fields.stream()
                .collect(Collectors.toMap(DocumentField::getFieldKey, Function.identity()));
        // 항목 순서는 ArcFieldKey 순서(화면 순서)로 고정한다
        List<Field> ordered = Arrays.stream(ArcFieldKey.values())
                .map(key -> byKey.get(key.key()))
                .filter(Objects::nonNull)
                .map(f -> field(f, confirmed))
                .toList();
        return new DocumentResponse(doc.getId(), doc.getDocumentType(), doc.getSide(), doc.getStatus(),
                OcrJob.of(ocrJob), ordered, kst(doc.getCreatedAt()), kst(doc.getConfirmedAt()));
    }

    private static Field field(DocumentField f, boolean confirmed) {
        String value = f.getConfirmedValue() != null ? f.getConfirmedValue() : f.getExtractedValue();
        boolean needsReview = !confirmed
                && (f.getConfidence() == null || f.getConfidence().compareTo(REVIEW_THRESHOLD) < 0);
        return new Field(f.getFieldKey(), value, f.getConfidence(), needsReview, f.getStatus(), f.getMasked());
    }

    private static OffsetDateTime kst(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.ofHours(9));
    }
}
