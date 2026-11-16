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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * mydata_bill_payments 테이블. 통신비·건강보험료·공과금의 달별 청구와 납부일(대안신용 연체 입력).
 * paid_on 이 null 이면 미납. (user_id, institution_id, bill_type, billing_month) UNIQUE.
 * 지금은 03 데모 시드(Mock)로만 들어온다.
 */
@Entity
@Table(name = "mydata_bill_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "bill_type", nullable = false, columnDefinition = "enum('TELECOM','HEALTH_INSURANCE','UTILITY')")
    private BillType billType;

    /** 청구 월(그 달 1일). */
    @Column(name = "billing_month", nullable = false)
    private LocalDate billingMonth;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "ingestion_batch_id", nullable = false)
    private Long ingestionBatchId;
}
