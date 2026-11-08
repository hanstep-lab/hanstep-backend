package com.hanstep.mydata.domain;

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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * financial_transactions 테이블. (account_id, external_txn_key) UNIQUE 로 재수집 중복을 막는다.
 * transacted_at 은 UTC, booked_date 는 KST 날짜. classification_source 는 DB 기본값(SOURCE).
 */
@Entity
@Table(name = "financial_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "external_txn_key", nullable = false, length = 100)
    private String externalTxnKey;

    @Column(name = "transacted_at", nullable = false)
    private Instant transactedAt;

    @Column(name = "booked_date", nullable = false)
    private LocalDate bookedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, columnDefinition = "enum('INCOME','EXPENSE')")
    private TransactionDirection direction;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "counterparty_name", length = 200)
    private String counterpartyName;

    @Column(name = "counterparty_bank", length = 100)
    private String counterpartyBank;

    @Column(name = "memo", length = 200)
    private String memo;

    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_kind", nullable = false, columnDefinition = "enum('NORMAL','OWN_TRANSFER',"
            + "'SAVINGS_TRANSFER','LOAN_DISBURSEMENT','LOAN_REPAYMENT','CARD_SETTLEMENT')")
    private TransactionKind transactionKind;

    public FinancialTransaction(Long userId, Long accountId, Long batchId, String externalTxnKey,
                                Instant transactedAt, LocalDate bookedDate, TransactionDirection direction,
                                BigDecimal amount, String currency, String counterpartyName,
                                String counterpartyBank, String memo, String categoryCode,
                                TransactionKind transactionKind) {
        this.userId = userId;
        this.accountId = accountId;
        this.batchId = batchId;
        this.externalTxnKey = externalTxnKey;
        this.transactedAt = transactedAt;
        this.bookedDate = bookedDate;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.counterpartyName = counterpartyName;
        this.counterpartyBank = counterpartyBank;
        this.memo = memo;
        this.categoryCode = categoryCode;
        this.transactionKind = transactionKind;
    }
}
