package com.hanstep.finance.domain;

/** 월 수지 항목의 근거. USER_INPUT=매달 나가는 돈에 직접 입력(CMT-02), TRANSACTIONS=Mock 거래 평균(FIN-05). */
public enum BalanceSource {
    USER_INPUT, TRANSACTIONS
}
