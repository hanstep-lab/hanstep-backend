package com.hanstep.document.dto;

import com.hanstep.aijob.domain.AiJob;
import com.hanstep.aijob.domain.AiJobStatus;
import com.hanstep.aijob.domain.PublishStatus;
import com.hanstep.common.time.KstDates;
import com.hanstep.document.domain.ArcFieldKey;
import com.hanstep.document.domain.Document;
import com.hanstep.document.domain.DocumentField;
import com.hanstep.document.domain.DocumentFieldStatus;
import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.domain.DocumentStatus;
import com.hanstep.document.domain.DocumentType;
import com.hanstep.document.service.ArcFieldValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
        @Schema(description = "문서 상태. UPLOADED(OCR 대기·처리 중, 세부는 ocrJob) → PENDING_CONFIRMATION(OCR 결과 확인 대기) → "
                + "CONFIRMED(확정, 원본 삭제). OCR 작업이 실패(ocrJob.status=FAILED)하면 FAILED 와 failureCode",
                example = "UPLOADED", allowableValues = {"UPLOADED", "PENDING_CONFIRMATION", "CONFIRMED", "FAILED"})
        DocumentStatus status,
        @Schema(description = "실패 코드(status=FAILED 일 때, OCR 작업의 error_code)", nullable = true, example = "OCR_FAILED")
        String failureCode,
        @Schema(description = "확정 전에 원본 보관 기한(업로드 24시간)이 지나 원본과 항목 값이 지워졌는가. true 면 status 는 "
                + "그대로이고 fields 의 value 가 모두 null 이다. 같은 사진을 다시 올리면 새 문서로 받는다(202, 새 documentId). "
                + "수정·확정은 막지 않는다(값을 다시 넣으면 확정된다). 확정된 문서는 false", example = "false")
        boolean originalExpired,
        @Schema(description = "가장 최근 OCR 작업. 진행·실패 상태는 여기서 본다", nullable = true)
        OcrJob ocrJob,
        @Schema(description = "OCR 항목. OCR 결과가 오기 전에는 빈 배열. 확정 후에는 value 가 null 이고 status·confidence 만 남는다")
        List<Field> fields,
        @Schema(description = "체류 만료일(stayExpiry 지금 값)까지 남은 일수(오늘 KST 기준, 지났으면 음수). 값이 없거나 "
                + "YYYY-MM-DD 가 아니면 null. 확정 후에는 null(ONB-04 의 visa.daysRemaining 을 쓴다)",
                nullable = true, example = "103")
        Long daysRemaining,
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
            String errorCode,
            @Schema(description = "다시 시도(POST /{documentId}/ocr-retry) 가능 여부. 발행 대기일 때 true. "
                    + "서버도 1분마다 자동으로 다시 보낸다", example = "false")
            boolean retryable
    ) {

        public static OcrJob of(AiJob job) {
            return job == null ? null : new OcrJob(job.getId(), job.getStatus(), job.getPublishStatus(),
                    job.getErrorCode(), job.isAwaitingPublish());
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
            @Schema(description = "확인 필요 표시. 확정 전, 사용자가 아직 확인·수정하지 않았고(EXTRACTED·MISSING) "
                    + "신뢰도가 0.8 미만이거나 없으면 true", example = "false")
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
        // documents.status 에는 FAILED 를 쓰지 않는다(진행·실패는 ai_jobs, 기준선 v1 결정 2). OCR 결과 전에 작업이 실패했으면 FAILED
        DocumentStatus status = doc.getStatus();
        String failureCode = doc.getFailureCode();
        if (status == DocumentStatus.UPLOADED && ocrJob != null && ocrJob.getStatus() == AiJobStatus.FAILED) {
            status = DocumentStatus.FAILED;
            failureCode = failureCode != null ? failureCode : ocrJob.getErrorCode();
        }
        return new DocumentResponse(doc.getId(), doc.getDocumentType(), doc.getSide(), status,
                status == DocumentStatus.FAILED ? failureCode : null, doc.originalDeletedBeforeConfirm(),
                OcrJob.of(ocrJob), ordered,
                confirmed ? null : daysRemaining(byKey.get(ArcFieldKey.STAY_EXPIRY.key())),
                kst(doc.getCreatedAt()), kst(doc.getConfirmedAt()));
    }

    // ONB-04 와 같은 계산(오늘 KST, ChronoUnit.DAYS). 값이 없거나 형식이 틀리면 null
    private static Long daysRemaining(DocumentField stayExpiry) {
        LocalDate date = stayExpiry == null ? null : ArcFieldValidator.parseDate(stayExpiry.currentValue());
        return date == null ? null : ChronoUnit.DAYS.between(LocalDate.now(KstDates.KST), date);
    }

    private static Field field(DocumentField f, boolean confirmed) {
        String value = f.getConfirmedValue() != null ? f.getConfirmedValue() : f.getExtractedValue();
        // 사용자가 이미 확인·수정한 항목(CONFIRMED·CORRECTED)은 다시 표시하지 않는다
        boolean unchecked = f.getStatus() == DocumentFieldStatus.EXTRACTED || f.getStatus() == DocumentFieldStatus.MISSING;
        boolean needsReview = !confirmed && unchecked
                && (f.getConfidence() == null || f.getConfidence().compareTo(REVIEW_THRESHOLD) < 0);
        return new Field(f.getFieldKey(), value, f.getConfidence(), needsReview, f.getStatus(), f.getMasked());
    }

    private static OffsetDateTime kst(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.ofHours(9));
    }
}
