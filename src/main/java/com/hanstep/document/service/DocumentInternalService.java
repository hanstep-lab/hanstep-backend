package com.hanstep.document.service;

import com.hanstep.aijob.service.AiJobStatusService;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.document.domain.ArcFieldKey;
import com.hanstep.document.domain.Document;
import com.hanstep.document.domain.DocumentField;
import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.domain.DocumentStatus;
import com.hanstep.document.dto.DocumentSourceResponse;
import com.hanstep.document.dto.OcrFieldResult;
import com.hanstep.document.dto.OcrResultRequest;
import com.hanstep.document.repository.DocumentFieldRepository;
import com.hanstep.document.repository.DocumentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AI Worker → Spring 등록증 OCR 내부 API(원본 위치 조회, OCR 결과 보고). OCR 값은 로그·오류 응답에 싣지 않는다. */
@Service
public class DocumentInternalService {

    private static final Logger log = LoggerFactory.getLogger(DocumentInternalService.class);

    /** 등록번호는 앞 6자리와 뒤 첫 자리만 남긴 형식으로만 받는다(전체 번호 저장 방지). */
    private static final Pattern MASKED_REGISTRATION_NO = Pattern.compile("^[0-9]{6}-[0-9]\\*{6}$");

    /** document_fields.confidence 는 DECIMAL(5,4). 저장 전에 맞춰야 재전송 비교가 DB 반올림과 어긋나지 않는다. */
    private static final int CONFIDENCE_SCALE = 4;

    private final DocumentRepository documentRepository;
    private final DocumentFieldRepository documentFieldRepository;
    private final AiJobStatusService aiJobStatusService;

    public DocumentInternalService(DocumentRepository documentRepository,
                                   DocumentFieldRepository documentFieldRepository,
                                   AiJobStatusService aiJobStatusService) {
        this.documentRepository = documentRepository;
        this.documentFieldRepository = documentFieldRepository;
        this.aiJobStatusService = aiJobStatusService;
    }

    @Transactional(readOnly = true)
    public DocumentSourceResponse source(Long documentId) {
        return documentRepository.findById(documentId)
                .map(DocumentSourceResponse::of)
                .orElseThrow(() -> notFound(documentId));
    }

    /**
     * OCR 결과 → document_fields 6행(value null 은 MISSING), 문서 PENDING_CONFIRMATION·모델 버전·처리 시간,
     * 같은 문서의 OCR 작업 COMPLETED. 한 트랜잭션에서 문서 → 작업 순으로 잠근다.
     * 이미 PENDING_CONFIRMATION 이고 저장된 결과와 같으면 재전송으로 보고 아무것도 바꾸지 않는다.
     */
    @Transactional
    public void recordOcrResult(Long documentId, OcrResultRequest request) {
        Map<String, OcrFieldResult> fields = validate(documentId, request);
        Document doc = documentRepository.findByIdForUpdate(documentId).orElseThrow(() -> notFound(documentId));
        if (doc.getSide() != request.side()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "side does not match",
                    Map.of("field", "side", "expected", doc.getSide().name()));
        }
        if (!doc.canRecordOcrResult()) {
            if (doc.getStatus() == DocumentStatus.PENDING_CONFIRMATION && sameAsRecorded(doc, request, fields)) {
                return;
            }
            String message = doc.getStatus() == DocumentStatus.PENDING_CONFIRMATION
                    ? "OCR result already recorded with different values" : "document is not waiting for OCR result";
            throw new BusinessException(ErrorCode.INVALID_STATE, message,
                    Map.of("documentId", documentId, "status", doc.getStatus().name()));
        }
        doc.recordOcrResult(request.modelVersion(), request.latencyMs());
        documentFieldRepository.saveAll(request.fields().stream()
                .map(f -> DocumentField.extracted(documentId, f.fieldKey(), f.value(), scale(f.confidence()),
                        f.isMasked()))
                .toList());
        aiJobStatusService.markOcrCompleted(documentId);
        log.info("document {} OCR result recorded ({}, {} ms)", documentId, request.modelVersion(), request.latencyMs());
    }

    // 형식은 @Valid 가 보고, 여기서는 경로·면·항목 키·등록번호 마스킹을 본다. 오류에 값은 싣지 않는다
    private static Map<String, OcrFieldResult> validate(Long documentId, OcrResultRequest request) {
        if (!documentId.equals(request.documentId())) {
            throw validation("documentId", "must match path documentId");
        }
        if (request.side() != DocumentSide.FRONT) {
            throw validation("side", "only FRONT is supported");
        }
        Map<String, OcrFieldResult> byKey = new HashMap<>();
        for (int i = 0; i < request.fields().size(); i++) {
            OcrFieldResult field = request.fields().get(i);
            String path = "fields[" + i + "]";
            ArcFieldKey key = ArcFieldKey.fromKey(field.fieldKey());
            if (key == null) {
                throw validation(path + ".fieldKey", "unknown fieldKey");
            }
            if (byKey.putIfAbsent(field.fieldKey(), field) != null) {
                throw validation(path + ".fieldKey", "duplicate fieldKey");
            }
            if (key == ArcFieldKey.REGISTRATION_NO) {
                if (!field.isMasked()) {
                    throw validation(path + ".isMasked", "registrationNo must be masked");
                }
                if (field.value() != null && !MASKED_REGISTRATION_NO.matcher(field.value()).matches()) {
                    throw validation(path + ".value", "registrationNo must be masked as ######-#******");
                }
            }
        }
        for (ArcFieldKey key : ArcFieldKey.values()) {
            if (!byKey.containsKey(key.key())) {
                throw validation("fields", "missing fieldKey " + key.key());
            }
        }
        return byKey;
    }

    // 재전송 판단: 모델 버전·처리 시간과 항목별 추출값·신뢰도·마스킹 여부가 모두 같은가.
    // 사용자가 확인 화면에서 고친 값(confirmed_value)은 보지 않는다
    private boolean sameAsRecorded(Document doc, OcrResultRequest request, Map<String, OcrFieldResult> fields) {
        if (!request.modelVersion().equals(doc.getOcrModelVersion())
                || !request.latencyMs().equals(doc.getOcrLatencyMs())) {
            return false;
        }
        List<DocumentField> recorded = documentFieldRepository.findByDocumentId(doc.getId());
        if (recorded.size() != fields.size()) {
            return false;
        }
        for (DocumentField saved : recorded) {
            OcrFieldResult field = fields.get(saved.getFieldKey());
            if (field == null
                    || !Objects.equals(saved.getExtractedValue(), field.value())
                    || !sameConfidence(saved.getConfidence(), scale(field.confidence()))
                    || !saved.getMasked().equals(field.isMasked())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameConfidence(BigDecimal saved, BigDecimal requested) {
        return saved == null ? requested == null : requested != null && saved.compareTo(requested) == 0;
    }

    private static BigDecimal scale(BigDecimal confidence) {
        return confidence == null ? null : confidence.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
    }

    private static BusinessException notFound(Long documentId) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("documentId", documentId));
    }

    private static BusinessException validation(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
