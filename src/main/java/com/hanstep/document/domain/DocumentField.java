package com.hanstep.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * document_fields 테이블. OCR 항목별 추출값과 사용자 확인값(확인 전 임시).
 * 문서 확정 후에는 값을 지우고 status·confidence 만 남긴다(OCR 정확도 지표용). 등록번호는 마스킹된 값만 저장한다.
 */
@Entity
@Table(name = "document_fields")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "field_key", nullable = false, length = 50)
    private String fieldKey;

    @Column(name = "extracted_value", length = 255)
    private String extractedValue;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "is_masked", nullable = false)
    private Boolean masked;

    @Column(name = "confirmed_value", length = 255)
    private String confirmedValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "enum('EXTRACTED','MISSING','CONFIRMED','CORRECTED')")
    private DocumentFieldStatus status;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** OCR 추출 결과 한 항목. 값이 없으면(null) MISSING. */
    public static DocumentField extracted(Long documentId, String fieldKey, String value, BigDecimal confidence,
                                          boolean masked) {
        DocumentField field = new DocumentField();
        field.documentId = documentId;
        field.fieldKey = fieldKey;
        field.extractedValue = value;
        field.confidence = confidence;
        field.masked = masked;
        field.status = value == null ? DocumentFieldStatus.MISSING : DocumentFieldStatus.EXTRACTED;
        return field;
    }
}
