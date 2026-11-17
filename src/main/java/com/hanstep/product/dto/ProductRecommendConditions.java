package com.hanstep.product.dto;

/**
 * PRD-01 화면에서 고른 조건(쿼리 파라미터). AI 계약 conditions 와 같은 이름·범위이며 모두 선택이다(null 이면 그 조건 없음).
 *
 * @param productType              적금 / 정기예금
 * @param maxTermMonths            받아들이는 가장 긴 계약 기간(개월), 1~120
 * @param monthlyAmountKrw         매달 넣을 금액(원), 1~100,000,000
 * @param accrual                  자유적립 / 정액적립
 * @param requireForeignerExplicit true 면 상품설명서에 외국인 가입이 명시된 상품만
 */
public record ProductRecommendConditions(ProductType productType, Integer maxTermMonths, Long monthlyAmountKrw,
                                         Accrual accrual, Boolean requireForeignerExplicit) {

    public static final int MAX_TERM_MONTHS = 120;
    public static final long MAX_MONTHLY_AMOUNT_KRW = 100_000_000L;

    public enum ProductType {
        SAVINGS, DEPOSIT
    }

    public enum Accrual {
        FLEXIBLE, FIXED
    }
}
