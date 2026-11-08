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
 * financial_accounts 테이블. (user_id, institution_id, external_account_key) UNIQUE 로 재수집 때 같은 계좌를 찾는다.
 * batch_id 는 최초 적재 배치. account_no_masked 는 화면 표시 전용.
 */
@Entity
@Table(name = "financial_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "external_account_key", nullable = false, length = 100)
    private String externalAccountKey;

    @Column(name = "account_no_masked", nullable = false, length = 40)
    private String accountNoMasked;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false,
            columnDefinition = "enum('DEMAND','SAVINGS','INSTALLMENT','FOREIGN_CURRENCY','CARD','OTHER')")
    private AccountType accountType;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    @Column(name = "balance_as_of")
    private Instant balanceAsOf;

    @Column(name = "is_salary_account", nullable = false)
    private boolean salaryAccount;

    @Column(name = "opened_on")
    private LocalDate openedOn;

    public FinancialAccount(Long userId, Long institutionId, Long batchId, String externalAccountKey,
                            String accountNoMasked, AccountType accountType, String productName, String currency,
                            BigDecimal balance, Instant balanceAsOf, boolean salaryAccount, LocalDate openedOn) {
        this.userId = userId;
        this.institutionId = institutionId;
        this.batchId = batchId;
        this.externalAccountKey = externalAccountKey;
        this.accountNoMasked = accountNoMasked;
        this.accountType = accountType;
        this.productName = productName;
        this.currency = currency;
        this.balance = balance;
        this.balanceAsOf = balanceAsOf;
        this.salaryAccount = salaryAccount;
        this.openedOn = openedOn;
    }

    /** 재수집: 잔액만 원천 값으로 갱신한다. */
    public void refreshBalance(BigDecimal balance, Instant balanceAsOf) {
        this.balance = balance;
        this.balanceAsOf = balanceAsOf;
    }
}
