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
import java.util.Objects;
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

    /** 지금 값: 사용자가 고친 값, 없으면 OCR 값. */
    public String currentValue() {
        return confirmedValue != null ? confirmedValue : extractedValue;
    }

    /** 사용자 확인·수정(DOC-03). OCR 값과 같으면 CONFIRMED, 다르면 CORRECTED. 마스킹 항목(등록번호)은 고칠 수 없다. */
    public void correct(String value, Instant at) {
        if (masked) {
            throw new IllegalStateException("masked field " + fieldKey + " cannot be corrected");
        }
        this.confirmedValue = value;
        this.status = Objects.equals(value, extractedValue) ? DocumentFieldStatus.CONFIRMED
                : DocumentFieldStatus.CORRECTED;
        this.confirmedAt = at;
    }

    /**
     * 문서 확정(DOC-04). 고치지 않은 OCR 값은 그대로 확정(CONFIRMED)으로 보고, 값은 모두 지운다(status·confidence 만 남김).
     * 끝까지 값이 없던 항목은 MISSING 그대로.
     */
    public void confirmAndClear(Instant at) {
        if (status == DocumentFieldStatus.EXTRACTED) {
            this.status = DocumentFieldStatus.CONFIRMED;
        }
        if (status != DocumentFieldStatus.MISSING && confirmedAt == null) {
            this.confirmedAt = at;
        }
        this.extractedValue = null;
        this.confirmedValue = null;
    }
}
