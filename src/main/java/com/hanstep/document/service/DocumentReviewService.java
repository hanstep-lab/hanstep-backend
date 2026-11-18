package com.hanstep.document.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.document.domain.ArcFieldKey;
import com.hanstep.document.domain.Document;
import com.hanstep.document.domain.DocumentField;
import com.hanstep.document.domain.DocumentStatus;
import com.hanstep.document.dto.DocumentFieldsUpdateRequest;
import com.hanstep.document.dto.DocumentResponse;
import com.hanstep.document.repository.DocumentFieldRepository;
import com.hanstep.document.repository.DocumentRepository;
import com.hanstep.document.storage.ArcImageStorage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록증 OCR 결과 확인·수정(DOC-03)과 확정(DOC-04). 확정값의 유일한 보관처는 visa_profiles 이고, 등록번호·영문 이름은
 * 담을 칸이 없어 저장하지 않는다. 값은 로그·오류 응답에 싣지 않는다.
 */
@Service
public class DocumentReviewService {

    private static final Logger log = LoggerFactory.getLogger(DocumentReviewService.class);
    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final DocumentFieldRepository documentFieldRepository;
    private final ArcImageStorage storage;
    private final ArcFieldValidator validator;
    private final JdbcTemplate jdbc;

    public DocumentReviewService(DocumentService documentService, DocumentRepository documentRepository,
                                 DocumentFieldRepository documentFieldRepository, ArcImageStorage storage,
                                 ArcFieldValidator validator, JdbcTemplate jdbc) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.documentFieldRepository = documentFieldRepository;
        this.storage = storage;
        this.validator = validator;
        this.jdbc = jdbc;
    }

    /**
     * DOC-03. PENDING_CONFIRMATION 일 때만(아니면 409). 보낸 항목만 바꾼다. 형식이 틀린 항목은 모두 모아 400.
     * 등록번호는 마스킹 값만 있어 고칠 수 없다.
     */
    @Transactional
    public DocumentResponse updateFields(Long userId, Long documentId, DocumentFieldsUpdateRequest request) {
        Document doc = lockOwn(userId, documentId);
        requirePendingConfirmation(doc);
        Map<String, DocumentField> byKey = fieldsByKey(doc);

        List<Map<String, String>> errors = validator.checkItems(request.fields(),
                key -> key == ArcFieldKey.REGISTRATION_NO ? "registrationNo cannot be edited" : null);
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("fields", errors));
        }

        Instant now = Instant.now();
        for (DocumentFieldsUpdateRequest.Item item : request.fields()) {
            DocumentField field = byKey.get(item.fieldKey());
            if (field == null) {
                // OCR 결과는 6개 항목을 모두 남기므로(MISSING 포함) 오지 않는다
                throw new IllegalStateException("document " + documentId + " has no field " + item.fieldKey());
            }
            field.correct(item.value().trim(), now);
        }
        documentFieldRepository.flush();
        return documentService.view(doc);
    }

    /**
     * DOC-04. PENDING_CONFIRMATION → CONFIRMED. 이미 CONFIRMED 면 그대로 200(다시 눌러도 같은 결과), 그 밖의 상태는 409.
     * 국적·생년월일·체류자격·체류 만료일이 모두 맞는 값이어야 한다(없거나 틀리면 422, DOC-03 으로 고친 뒤 다시).
     * 한 트랜잭션에서 visa_profiles 반영 → 항목 값 삭제 → 문서 CONFIRMED·object_key NULL → R2 원본 삭제.
     * R2 삭제가 실패하면 롤백되어 확정 전 상태로 남는다(다시 확정하면 된다).
     */
    @Transactional
    public DocumentResponse confirm(Long userId, Long documentId) {
        Document doc = lockOwn(userId, documentId);
        if (doc.getStatus() == DocumentStatus.CONFIRMED) {
            return documentService.view(doc);
        }
        requirePendingConfirmation(doc);
        Map<String, DocumentField> byKey = fieldsByKey(doc);

        List<Map<String, String>> errors = new ArrayList<>();
        for (ArcFieldKey key : ArcFieldValidator.PROFILE_KEYS) {
            DocumentField field = byKey.get(key.key());
            String value = field == null ? null : field.currentValue();
            if (value == null || value.isBlank()) {
                errors.add(ArcFieldValidator.error(key.key(), "required"));
            } else if (validator.invalidReason(key, value) != null) {
                errors.add(ArcFieldValidator.error(key.key(), validator.invalidReason(key, value)));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_DATA, "fields need confirmation",
                    Map.of("fields", errors));
        }

        upsertVisaProfile(userId, doc.getId(),
                validator.visaTypeIdOrNull(byKey.get(ArcFieldKey.VISA_TYPE.key()).currentValue()),
                LocalDate.parse(byKey.get(ArcFieldKey.STAY_EXPIRY.key()).currentValue()),
                byKey.get(ArcFieldKey.NATIONALITY.key()).currentValue(),
                LocalDate.parse(byKey.get(ArcFieldKey.BIRTH_DATE.key()).currentValue()));

        Instant now = Instant.now();
        byKey.values().forEach(f -> f.confirmAndClear(now));
        String original = doc.confirm(now);
        documentRepository.flush();
        if (original != null) {
            storage.delete(original);
        }
        log.info("document {} confirmed, original deleted", doc.getId());
        return documentService.view(doc);
    }

    // 확정 전 값은 사용자 정보와 함께 바뀌지 않게 문서 행을 잠근다(OCR 결과 보고와 같은 잠금)
    private Document lockOwn(Long userId, Long documentId) {
        return documentRepository.findByIdForUpdate(documentId)
                .filter(doc -> doc.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("documentId", documentId)));
    }

    private static void requirePendingConfirmation(Document doc) {
        if (doc.getStatus() != DocumentStatus.PENDING_CONFIRMATION) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "document is not waiting for confirmation",
                    Map.of("documentId", doc.getId(), "status", doc.getStatus().name()));
        }
    }

    private Map<String, DocumentField> fieldsByKey(Document doc) {
        return documentFieldRepository.findByDocumentId(doc.getId()).stream()
                .collect(Collectors.toMap(DocumentField::getFieldKey, Function.identity()));
    }

    // 사용자당 1행(uq_visa_profiles_user). 입국일·이전 체류 개월·증빙 상태는 OCR 항목이 아니라 그대로 둔다
    private void upsertVisaProfile(Long userId, Long documentId, long visaTypeId, LocalDate stayExpiresOn,
                                   String nationalityCode, LocalDate birthDate) {
        jdbc.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, nationality_code, birth_date,
                  source_document_id)
                VALUES (?, ?, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE visa_type_id = new.visa_type_id, stay_expires_on = new.stay_expires_on,
                  nationality_code = new.nationality_code, birth_date = new.birth_date,
                  source_document_id = new.source_document_id
                """, userId, visaTypeId, stayExpiresOn, nationalityCode, birthDate, documentId);
    }
}
