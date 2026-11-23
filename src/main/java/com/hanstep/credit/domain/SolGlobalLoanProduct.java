package com.hanstep.credit.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 신한은행 SOL 글로벌론(외국인 전용 직장인 신용대출) 상품 조건. 재현 정리(신한은행 SOL 글로벌론 안내) 기준.
 * TODO: 출처 URL·확인일이 아직 없다. 확인되면 여기에 적고 BASIS 문구도 맞춘다.
 * 금리 숫자는 넣지 않는다(상품설명서에서 확인).
 */
public final class SolGlobalLoanProduct {

    private SolGlobalLoanProduct() {
    }

    public static final String PRODUCT_TAG = "신한은행 대출 상품";
    public static final String NAME = "SOL 글로벌론";
    public static final String SUMMARY = "외국인 전용 직장인 신용대출이에요.";
    // TODO: 출처 URL·확인일 확인 후 보강
    public static final String BASIS = "신한은행 상품 안내 기준";

    /** 영어 문구(preferred_language 가 ko 가 아닌 사용자). */
    public static final String PRODUCT_TAG_EN = "Shinhan Bank loan product";
    public static final String NAME_EN = "SOL Global Loan";
    public static final String SUMMARY_EN = "A credit loan for employed foreign residents.";
    public static final String BASIS_EN = "Based on Shinhan Bank product information";

    /** 대상 체류자격. E-7 은 세부 자격(E-7-1~E-7-4 등)을 모두 포함한다. */
    public static final List<String> VISA_CODES = List.of("E-9", "E-7", "F-2", "F-5");

    /** 대상 국적(ISO 3166-1 alpha-3 → 한글 이름). 순서는 안내 문구 순서. */
    public static final Map<String, String> NATIONALITIES;

    static {
        Map<String, String> nationalities = new LinkedHashMap<>();
        nationalities.put("VNM", "베트남");
        nationalities.put("IDN", "인도네시아");
        nationalities.put("KHM", "캄보디아");
        nationalities.put("KAZ", "카자흐스탄");
        nationalities.put("UZB", "우즈베키스탄");
        nationalities.put("PHL", "필리핀");
        nationalities.put("NPL", "네팔");
        NATIONALITIES = Collections.unmodifiableMap(nationalities);
    }

    /** 대상 국적 영어 이름. 키와 순서는 NATIONALITIES 와 같다. */
    public static final Map<String, String> NATIONALITIES_EN;

    static {
        Map<String, String> nationalities = new LinkedHashMap<>();
        nationalities.put("VNM", "Vietnam");
        nationalities.put("IDN", "Indonesia");
        nationalities.put("KHM", "Cambodia");
        nationalities.put("KAZ", "Kazakhstan");
        nationalities.put("UZB", "Uzbekistan");
        nationalities.put("PHL", "Philippines");
        nationalities.put("NPL", "Nepal");
        NATIONALITIES_EN = Collections.unmodifiableMap(nationalities);
    }

    /** 체류 만료까지 이 개월 수를 넘게 남아야 한다(초과). */
    public static final int MIN_MONTHS_TO_EXPIRY = 6;
    /** 재직 3개월 이상. */
    public static final int MIN_EMPLOYMENT_MONTHS = 3;
    /** 신한은행 급여이체 최근 1개월 이상. */
    public static final int MIN_SHINHAN_PAYROLL_MONTHS = 1;
    /** 연소득 2,000만 원 이상. */
    public static final BigDecimal MIN_ANNUAL_INCOME = BigDecimal.valueOf(20_000_000L);

    /** 한도 최대 5,000만 원. */
    public static final BigDecimal MAX_LIMIT = BigDecimal.valueOf(50_000_000L);
    /** 한도 산식 Min(연소득 × 80%, 연소득 ÷ 12 × 40% × 대출개월수) − 당·타행 대출. */
    public static final BigDecimal INCOME_RATIO = new BigDecimal("0.8");
    public static final BigDecimal REPAYMENT_RATIO = new BigDecimal("0.4");

    /** 대출 기간 6~36개월(월 단위). 만기일은 체류기간 만료일 전월까지. */
    public static final int MIN_TERM_MONTHS = 6;
    public static final int MAX_TERM_MONTHS = 36;

    public static final String REPAYMENT_METHOD = "원금분할상환";
    public static final String REPAYMENT_METHOD_EN = "Equal principal installments";

    /** 체류자격이 대상인지. E-7 은 E-7 자체와 E-7-n 세부 자격을 모두 대상으로 본다. */
    public static boolean isEligibleVisa(String visaCode) {
        if (visaCode == null) {
            return false;
        }
        return VISA_CODES.contains(visaCode) || visaCode.startsWith("E-7-");
    }
}
