package com.hanstep.commitment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** monthly_commitments 테이블. 전체 교체(CMT-02) 때 빠진 항목은 지우지 않고 is_active=false. */
@Entity
@Table(name = "monthly_commitments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyCommitment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "commitment_type", nullable = false,
            columnDefinition = "enum('REMITTANCE','RENT','UTILITY','TELECOM','MANDATORY_SAVING','LIVING_MINIMUM',"
                    + "'VARIABLE_EXPENSE','OTHER')")
    private CommitmentType commitmentType;

    @Column(name = "label", length = 100)
    private String label;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, columnDefinition = "enum('USER','DETECTED_CONFIRMED')")
    private CommitmentSource source;

    @Column(name = "detected_from_txn_id")
    private Long detectedFromTxnId;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public MonthlyCommitment(Long userId, CommitmentType commitmentType, String label, BigDecimal amount,
                             String currency, CommitmentSource source, Long detectedFromTxnId) {
        this.userId = userId;
        this.commitmentType = commitmentType;
        this.label = label;
        this.amount = amount;
        this.currency = currency;
        this.source = source;
        this.detectedFromTxnId = detectedFromTxnId;
        this.active = true;
    }

    /** 요청 항목과 내용이 모두 같으면 기존 행을 그대로 둔다(CMT-02). */
    public boolean sameAs(CommitmentType commitmentType, String label, BigDecimal amount, String currency,
                          CommitmentSource source, Long detectedFromTxnId) {
        return this.commitmentType == commitmentType
                && Objects.equals(this.label, label)
                && this.amount.compareTo(amount) == 0
                && this.currency.equals(currency)
                && this.source == source
                && Objects.equals(this.detectedFromTxnId, detectedFromTxnId);
    }

    public void deactivate() {
        this.active = false;
    }
}
