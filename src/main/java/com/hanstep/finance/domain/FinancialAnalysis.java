package com.hanstep.finance.domain;

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
 * financial_analyses 테이블. append-only(덮어쓰지 않고 새 행), (user_id, request_key) UNIQUE 가 멱등키.
 * 금액은 INT-01 결과를 그대로 옮긴 것이다(LLM 수치 아님). 시각 칸은 UTC.
 * remittance_ratio·monthly_json·category_json 은 INT-01 이 주지 않아 매핑하지 않는다(NULL).
 */
@Entity
@Table(name = "financial_analyses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_key", nullable = false, length = 100)
    private String requestKey;

    /** 분석에 쓴 적재 배치(응답 isMock 의 근거). 배치가 지워지면 NULL. */
    @Column(name = "source_batch_id")
    private Long sourceBatchId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "enum('COMPLETED','FAILED')")
    private AnalysisStatus status;

    @Column(name = "engine_version", nullable = false, length = 40)
    private String engineVersion;

    @Column(name = "source_txn_count", nullable = false, columnDefinition = "int unsigned")
    private Integer sourceTxnCount;

    @Column(name = "source_fingerprint", columnDefinition = "char(64)")
    private String sourceFingerprint;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "avg_monthly_income", precision = 18, scale = 2)
    private BigDecimal avgMonthlyIncome;

    /** 고정 + 송금 + 변동. */
    @Column(name = "avg_monthly_expense", precision = 18, scale = 2)
    private BigDecimal avgMonthlyExpense;

    @Column(name = "avg_monthly_remittance", precision = 18, scale = 2)
    private BigDecimal avgMonthlyRemittance;

    @Column(name = "avg_monthly_fixed", precision = 18, scale = 2)
    private BigDecimal avgMonthlyFixed;

    @Column(name = "avg_monthly_variable", precision = 18, scale = 2)
    private BigDecimal avgMonthlyVariable;

    @Column(name = "variable_monthly_min", precision = 18, scale = 2)
    private BigDecimal variableMonthlyMin;

    @Column(name = "variable_monthly_max", precision = 18, scale = 2)
    private BigDecimal variableMonthlyMax;

    /** INT-01 spare(음수 가능). */
    @Column(name = "avg_monthly_surplus", precision = 18, scale = 2)
    private BigDecimal avgMonthlySurplus;

    @Column(name = "observed_month_count", nullable = false, columnDefinition = "smallint unsigned")
    private Integer observedMonthCount;

    // 아래 JSON 칸은 문자열 배열·객체 배열 JSON 그대로 저장
    @Column(name = "missing_months_json", columnDefinition = "json")
    private String missingMonthsJson;

    @Column(name = "partial_months_json", columnDefinition = "json")
    private String partialMonthsJson;

    @Column(name = "warnings_json", columnDefinition = "json")
    private String warningsJson;

    /** [{"transactionId": 1, "reason": "CARD_SETTLEMENT"}, ...] */
    @Column(name = "excluded_json", columnDefinition = "json")
    private String excludedJson;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 분석 입력(배치·기간·거래 수·지문). COMPLETED·FAILED 공통. */
    public record Source(Long userId, String requestKey, Long batchId, LocalDate periodFrom, LocalDate periodTo,
                         String engineVersion, int txnCount, String fingerprint) {
    }

    /** INT-01 결과 금액. 꽉 찬 달이 없으면 모두 null. */
    public record Amounts(BigDecimal income, BigDecimal fixed, BigDecimal remittance, BigDecimal variableAvg,
                          BigDecimal variableMin, BigDecimal variableMax, BigDecimal spare) {
    }

    /** INT-01 데이터 품질·제외 거래(JSON 문자열). */
    public record Quality(int observedMonthCount, String missingMonthsJson, String partialMonthsJson,
                          String warningsJson, String excludedJson) {
    }

    public static FinancialAnalysis completed(Source source, Amounts amounts, Quality quality, Instant createdAt) {
        FinancialAnalysis a = base(source, AnalysisStatus.COMPLETED, createdAt);
        a.avgMonthlyIncome = amounts.income();
        a.avgMonthlyFixed = amounts.fixed();
        a.avgMonthlyRemittance = amounts.remittance();
        a.avgMonthlyVariable = amounts.variableAvg();
        a.variableMonthlyMin = amounts.variableMin();
        a.variableMonthlyMax = amounts.variableMax();
        a.avgMonthlySurplus = amounts.spare();
        a.avgMonthlyExpense = amounts.fixed() == null || amounts.remittance() == null || amounts.variableAvg() == null
                ? null : amounts.fixed().add(amounts.remittance()).add(amounts.variableAvg());
        a.observedMonthCount = quality.observedMonthCount();
        a.missingMonthsJson = quality.missingMonthsJson();
        a.partialMonthsJson = quality.partialMonthsJson();
        a.warningsJson = quality.warningsJson();
        a.excludedJson = quality.excludedJson();
        return a;
    }

    public static FinancialAnalysis failed(Source source, String errorCode, Instant createdAt) {
        FinancialAnalysis a = base(source, AnalysisStatus.FAILED, createdAt);
        a.observedMonthCount = 0;
        a.errorCode = errorCode;
        return a;
    }

    private static FinancialAnalysis base(Source source, AnalysisStatus status, Instant createdAt) {
        FinancialAnalysis a = new FinancialAnalysis();
        a.userId = source.userId();
        a.requestKey = source.requestKey();
        a.sourceBatchId = source.batchId();
        a.periodFrom = source.periodFrom();
        a.periodTo = source.periodTo();
        a.status = status;
        a.engineVersion = source.engineVersion();
        a.sourceTxnCount = source.txnCount();
        a.sourceFingerprint = source.fingerprint();
        a.currency = "KRW";
        a.createdAt = createdAt;
        return a;
    }
}
