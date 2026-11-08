package com.hanstep.mydata.domain;

/** 중복 집계 차단용 거래 종류(financial_transactions.transaction_kind). */
public enum TransactionKind {
    NORMAL, OWN_TRANSFER, SAVINGS_TRANSFER, LOAN_DISBURSEMENT, LOAN_REPAYMENT, CARD_SETTLEMENT
}
