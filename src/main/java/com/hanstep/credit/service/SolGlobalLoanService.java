package com.hanstep.credit.service;

import static com.hanstep.credit.domain.SolGlobalLoanProduct.MIN_ANNUAL_INCOME;
import static com.hanstep.credit.domain.SolGlobalLoanProduct.MIN_EMPLOYMENT_MONTHS;
import static com.hanstep.credit.domain.SolGlobalLoanProduct.MIN_MONTHS_TO_EXPIRY;
import static com.hanstep.credit.domain.SolGlobalLoanProduct.MIN_SHINHAN_PAYROLL_MONTHS;
import static com.hanstep.credit.domain.SolGlobalLoanProduct.NATIONALITIES;

import com.hanstep.common.time.KstDates;
import com.hanstep.credit.domain.SolGlobalLoanProduct;
import com.hanstep.credit.dto.AltCreditInputs;
import com.hanstep.credit.dto.SolGlobalLoanLimitEstimate;
import com.hanstep.credit.dto.SolGlobalLoanLimitEstimate.IncomeSource;
import com.hanstep.credit.dto.SolGlobalLoanResponse;
import com.hanstep.credit.dto.SolGlobalLoanResponse.Condition;
import com.hanstep.credit.dto.SolGlobalLoanResponse.Eligibility;
import com.hanstep.credit.dto.SolGlobalLoanResponse.TermRow;
import com.hanstep.credit.dto.SolGlobalLoanResponse.Terms;
import com.hanstep.credit.service.SolGlobalLoanCalculator.AnnualIncome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * SOL 글로벌론 상품 상세(CR-002). 상품 안내라 CREDIT_EVALUATION 동의를 요구하지 않고 AI 를 부르지 않는다. 저장하지 않는다.
 * <ul>
 *   <li>급여 입금 개월·신한 급여 계좌·만료까지 개월은 대안신용 입력(AltCreditInputReader)을 그대로 쓴다.
 *       그래서 입력이 모자라면 대안신용과 같은 422 INSUFFICIENT_DATA 가 난다</li>
 *   <li>체류자격 코드·국적·만료일, 연소득(임시), 기존 대출 잔액만 여기서 더 읽는다</li>
 * </ul>
 */
@Service
public class SolGlobalLoanService {

    static final String NOTICE = "신한걸음 자체 모의 계산이에요. DSR·타행 대출 심사는 반영하지 않았고, 실제 대출 가능 여부와 "
            + "금리는 은행 심사에 따라 달라요.";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final AltCreditInputReader inputReader;
    private final JdbcTemplate jdbcTemplate;

    public SolGlobalLoanService(AltCreditInputReader inputReader, JdbcTemplate jdbcTemplate) {
        this.inputReader = inputReader;
        this.jdbcTemplate = jdbcTemplate;
    }

    public SolGlobalLoanResponse get(Long userId) {
        LocalDate today = LocalDate.now(KstDates.KST);
        return get(userId, today);
    }

    SolGlobalLoanResponse get(Long userId, LocalDate today) {
        // 체류 프로필·Mock 거래가 없으면 여기서 422(대안신용과 같은 reason)
        AltCreditInputs inputs = inputReader.read(userId, today, false);
        Profile profile = profile(userId);
        AnnualIncome income = annualIncome(userId);
        BigDecimal existingLoans = existingLoans(userId);

        List<Condition> conditions = List.of(salary(inputs), visa(profile.visaCode()),
                nationality(profile.nationalityCode()), stay(profile.stayExpiresOn(), inputs.monthsToExpiry(), today),
                income(income));
        long met = conditions.stream().filter(Condition::met).count();
        Eligibility eligibility = new Eligibility("가입 조건 확인", "마이데이터·외국인등록증·소득 정보로 확인했어요",
                conditions.size() + "개 중 " + met + "개 충족", conditions);

        SolGlobalLoanLimitEstimate estimate = SolGlobalLoanCalculator.estimate(today, profile.stayExpiresOn(), income,
                existingLoans, met == conditions.size());
        return new SolGlobalLoanResponse(SolGlobalLoanProduct.PRODUCT_TAG, SolGlobalLoanProduct.NAME,
                SolGlobalLoanProduct.SUMMARY, eligibility, terms(), NOTICE, estimate, true);
    }

    // 재직 3개월 이상은 연속 급여 입금 개월로, 신한 급여이체 최근 1개월 이상은 신한 급여 계좌 + 급여 입금 1개월 이상으로 본다(임시)
    private static Condition salary(AltCreditInputs inputs) {
        int months = inputs.salaryMonths();
        boolean met = months >= MIN_EMPLOYMENT_MONTHS && months >= MIN_SHINHAN_PAYROLL_MONTHS
                && inputs.shinhanPayroll();
        return new Condition("salary", "재직·급여이체 조건",
                "재직기간 3개월 이상 · 신한은행 급여이체 최근 1개월 이상 · 현재 급여 입금 " + months + "개월 연속, 신한 급여 계좌 "
                        + (inputs.shinhanPayroll() ? "있음" : "없음"), met);
    }

    private static Condition visa(String visaCode) {
        return new Condition("visa", "대상 체류자격", String.join("·", SolGlobalLoanProduct.VISA_CODES) + " 중 하나 · "
                + (visaCode == null ? "체류자격 정보 없음" : "현재 " + visaCode),
                SolGlobalLoanProduct.isEligibleVisa(visaCode));
    }

    private static Condition nationality(String code) {
        String current = code == null ? "국적 정보 없음" : "현재 " + NATIONALITIES.getOrDefault(code, code);
        return new Condition("nationality", "대상 국적", String.join("·", NATIONALITIES.values()) + " · " + current,
                code != null && NATIONALITIES.containsKey(code));
    }

    // 6개월 초과 = 만료일이 오늘 + 6개월보다 뒤. 남은 개월 표시는 대안신용 입력(꽉 찬 달 수)
    private static Condition stay(LocalDate expiresOn, Integer monthsToExpiry, LocalDate today) {
        String desc;
        if (expiresOn == null) {
            desc = "체류 만료일 정보 없음";
        } else if (!expiresOn.isAfter(today)) {
            desc = expiresOn.format(DATE) + " 만료 · 만료됨";
        } else if (monthsToExpiry == null || monthsToExpiry == 0) {
            desc = expiresOn.format(DATE) + " 만료 · 1개월 미만 남음";
        } else {
            desc = expiresOn.format(DATE) + " 만료 · 약 " + monthsToExpiry + "개월 남음";
        }
        boolean met = expiresOn != null && expiresOn.isAfter(today.plusMonths(MIN_MONTHS_TO_EXPIRY));
        return new Condition("stay", "체류 만료까지 6개월 초과", desc, met);
    }

    private static Condition income(AnnualIncome income) {
        String desc;
        if (income.amount() == null) {
            desc = "연소득 정보 없음";
        } else {
            String basis = income.source() == IncomeSource.INCOME_RECORD
                    ? income.year() + "년 소득 확인 기준" : "고용계약 연봉 기준";
            desc = "현재 연 " + manwon(income.amount()) + "(" + basis + ")";
        }
        boolean met = income.amount() != null && income.amount().compareTo(MIN_ANNUAL_INCOME) >= 0;
        return new Condition("income", "연소득 2,000만 원 이상", desc, met);
    }

    private static Terms terms() {
        return new Terms("상품 조건", SolGlobalLoanProduct.BASIS, List.of(
                new TermRow("limit", "대출 한도", "최대 5,000만 원"),
                new TermRow("term", "대출 기간", "6개월 ~ 36개월(만기는 체류 만료일 전월까지)"),
                new TermRow("repayment", "상환 방식", SolGlobalLoanProduct.REPAYMENT_METHOD),
                new TermRow("rate", "금리", "상품설명서에서 확인")));
    }

    // 만 원 미만 버림. 예: 32,100,000 → "3,210만 원"
    static String manwon(BigDecimal amount) {
        long man = amount.divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.DOWN).longValueExact();
        return new DecimalFormat("#,##0").format(man) + "만 원";
    }

    private record Profile(String visaCode, String nationalityCode, LocalDate stayExpiresOn) {
    }

    // inputReader.read 가 체류 프로필이 있는 것을 이미 확인했다
    private Profile profile(Long userId) {
        return jdbcTemplate.queryForObject("""
                        SELECT vt.code AS visa_type_code, vp.nationality_code, vp.stay_expires_on
                        FROM visa_profiles vp LEFT JOIN visa_types vt ON vt.id = vp.visa_type_id
                        WHERE vp.user_id = ?""",
                (rs, i) -> new Profile(rs.getString("visa_type_code"), rs.getString("nationality_code"),
                        rs.getObject("stay_expires_on", LocalDate.class)),
                userId);
    }

    /**
     * 연소득(임시): income_records 의 가장 최근 VERIFIED 연도(같은 해에 출처가 여럿이면 DOCUMENT 우선),
     * 없으면 현 직장(ended_on 없음) 고용계약 연봉.
     */
    private AnnualIncome annualIncome(Long userId) {
        List<AnnualIncome> records = jdbcTemplate.query("""
                        SELECT income_year, annual_amount FROM income_records
                        WHERE user_id = ? AND verification_status = 'VERIFIED'
                        ORDER BY income_year DESC, FIELD(source, 'DOCUMENT', 'USER_DECLARED', 'TRANSACTION_ESTIMATE')
                        LIMIT 1""",
                (rs, i) -> new AnnualIncome(rs.getBigDecimal("annual_amount"), IncomeSource.INCOME_RECORD,
                        rs.getInt("income_year")),
                userId);
        if (!records.isEmpty()) {
            return records.get(0);
        }
        List<BigDecimal> salaries = jdbcTemplate.query("""
                        SELECT contract_annual_salary FROM employment_histories
                        WHERE user_id = ? AND ended_on IS NULL AND contract_annual_salary IS NOT NULL
                        ORDER BY started_on DESC, id DESC LIMIT 1""",
                (rs, i) -> rs.getBigDecimal("contract_annual_salary"),
                userId);
        return salaries.isEmpty() ? AnnualIncome.NONE
                : new AnnualIncome(salaries.get(0), IncomeSource.CONTRACT_SALARY, null);
    }

    // 당·타행 구분 없이 ACTIVE 대출 잔액 합
    private BigDecimal existingLoans(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(outstanding_balance), 0) FROM loans WHERE user_id = ? AND status = 'ACTIVE'",
                BigDecimal.class, userId);
    }
}
