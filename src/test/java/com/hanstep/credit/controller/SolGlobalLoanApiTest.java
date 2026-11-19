package com.hanstep.credit.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.support.TestUsers;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * SOL 글로벌론 상품 상세 API. 테스트 사용자·체류 프로필·Mock 거래·소득을 직접 넣는다(테스트마다 롤백).
 * 동의는 넣지 않는다(상품 안내라 CREDIT_EVALUATION 동의를 요구하지 않음). Reyes 시드는 SolGlobalLoanReyesSeedTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SolGlobalLoanApiTest {

    private static final String URL = "/api/v1/credit/loans/sol-global";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private LocalDate today;
    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(KstDates.KST);
        userId = TestUsers.create(jdbc, "test-sol-global", "TEST SOL GLOBAL");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    @Test
    void 조건을_모두_충족하면_기존_대출을_빼고_한도를_계산한다() throws Exception {
        // 만료가 5년 뒤라 기간 36개월. 30,000,000 × 0.8 = 24,000,000 < 36,000,000 → 24,000,000 − 3,500,000
        LocalDate expiresOn = today.plusYears(5);
        visaProfile("E-9", "VNM", expiresOn);
        long batchId = transactions(6, true);
        incomeRecord(2025, 30_000_000, "DOCUMENT", "VERIFIED");
        loan(batchId, "loan-a", 2_000_000, "ACTIVE");
        loan(batchId, "loan-b", 1_500_000, "ACTIVE");
        loan(batchId, "loan-closed", 9_000_000, "CLOSED");

        solGlobal()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productTag").value("신한은행 대출 상품"))
                .andExpect(jsonPath("$.name").value("SOL 글로벌론"))
                .andExpect(jsonPath("$.summary").value("외국인 전용 직장인 신용대출이에요."))
                .andExpect(jsonPath("$.isMock").value(true))
                .andExpect(jsonPath("$.notice").value("신한걸음 자체 모의 계산이에요. DSR·타행 대출 심사는 반영하지 "
                        + "않았고, 실제 대출 가능 여부와 금리는 은행 심사에 따라 달라요."))
                .andExpect(jsonPath("$.eligibility.resultLabel").value("5개 중 5개 충족"))
                .andExpect(jsonPath("$.eligibility.conditions[*].key")
                        .value(contains("salary", "visa", "nationality", "stay", "income")))
                .andExpect(jsonPath("$.eligibility.conditions[*].met")
                        .value(everyItem(is(true))))
                .andExpect(jsonPath("$.eligibility.conditions[0].desc").value("재직기간 3개월 이상 · 신한은행 급여이체 "
                        + "최근 1개월 이상 · 현재 급여 입금 6개월 연속, 신한 급여 계좌 있음"))
                .andExpect(jsonPath("$.eligibility.conditions[1].desc").value("E-9·E-7·F-2·F-5 중 하나 · 현재 E-9"))
                .andExpect(jsonPath("$.eligibility.conditions[2].desc").value(
                        "베트남·인도네시아·캄보디아·카자흐스탄·우즈베키스탄·필리핀·네팔 · 현재 베트남"))
                .andExpect(jsonPath("$.eligibility.conditions[3].desc").value(
                        expiresOn.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) + " 만료 · 약 60개월 남음"))
                .andExpect(jsonPath("$.eligibility.conditions[4].desc").value("현재 연 3,000만 원(2025년 소득 확인 기준)"))
                .andExpect(jsonPath("$.terms.basis").value("신한은행 상품 안내 기준"))
                .andExpect(jsonPath("$.terms.rows[*].key").value(contains("limit", "term", "repayment", "rate")))
                .andExpect(jsonPath("$.terms.rows[*].label").value(contains("대출 한도", "대출 기간", "상환 방식", "금리")))
                .andExpect(jsonPath("$.terms.rows[?(@.key == 'rate')].value").value("상품설명서에서 확인"))
                .andExpect(jsonPath("$.limitEstimate.maxTermMonths").value(36))
                .andExpect(jsonPath("$.limitEstimate.byIncomeRatio.amount").value(24_000_000))
                .andExpect(jsonPath("$.limitEstimate.byRepayment.amount").value(36_000_000))
                .andExpect(jsonPath("$.limitEstimate.existingLoans.amount").value(3_500_000))
                .andExpect(jsonPath("$.limitEstimate.estimatedLimit.amount").value(20_500_000))
                .andExpect(jsonPath("$.limitEstimate.estimatedLimit.currency").value("KRW"))
                .andExpect(jsonPath("$.limitEstimate.reasonCode").doesNotExist())
                .andExpect(jsonPath("$.limitEstimate.incomeSource").value("INCOME_RECORD"))
                .andExpect(jsonPath("$.limitEstimate.incomeYear").value(2025))
                .andExpect(jsonPath("$.limitEstimate.dsrApplied").value(false))
                .andExpect(jsonPath("$.limitEstimate.asOfDate").value(today.toString()));
    }

    @Test
    void 신한_급여_계좌가_없으면_salary_미충족이고_한도_null() throws Exception {
        eligibleData();
        jdbc.update("UPDATE financial_accounts SET is_salary_account = FALSE WHERE user_id = ?", userId);

        expectUnmet("salary", "현재 급여 입금 6개월 연속, 신한 급여 계좌 없음");
    }

    @Test
    void 연속_급여_입금이_3개월_미만이면_salary_미충족() throws Exception {
        visaProfile("E-9", "PHL", today.plusYears(2));
        transactions(2, true);
        incomeRecord(2025, 30_000_000, "DOCUMENT", "VERIFIED");

        expectUnmet("salary", "현재 급여 입금 2개월 연속, 신한 급여 계좌 있음");
    }

    @Test
    void 대상이_아닌_체류자격이면_visa_미충족() throws Exception {
        visaProfile("H-2", "PHL", today.plusYears(2));
        transactions(6, true);
        incomeRecord(2025, 30_000_000, "DOCUMENT", "VERIFIED");

        expectUnmet("visa", "E-9·E-7·F-2·F-5 중 하나 · 현재 H-2");
    }

    @Test
    void E_7_4는_E_7_대상에_포함된다() throws Exception {
        visaProfile("E-7-4", "PHL", today.plusYears(2));
        transactions(6, true);
        incomeRecord(2025, 30_000_000, "DOCUMENT", "VERIFIED");

        solGlobal()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility.conditions[1].met").value(true))
                .andExpect(jsonPath("$.eligibility.resultLabel").value("5개 중 5개 충족"));
    }

    @Test
    void 대상이_아닌_국적이면_nationality_미충족() throws Exception {
        visaProfile("E-9", "CHN", today.plusYears(2));
        transactions(6, true);
        incomeRecord(2025, 30_000_000, "DOCUMENT", "VERIFIED");

        expectUnmet("nationality", "현재 CHN");
    }

    @Test
    void 국적이_없으면_nationality_미충족() throws Exception {
        visaProfile("E-9", null, today.plusYears(2));
        transactions(6, true);
        incomeRecord(2025, 30_000_000, "DOCUMENT", "VERIFIED");

        expectUnmet("nationality", "국적 정보 없음");
    }

    @Test
    void 만료까지_딱_6개월이면_stay_미충족() throws Exception {
        visaProfile("E-9", "PHL", today.plusMonths(6));
        transactions(6, true);
        incomeRecord(2025, 30_000_000, "DOCUMENT", "VERIFIED");

        expectUnmet("stay", "약 6개월 남음");
    }

    @Test
    void 만료까지_6개월_초과여도_만기_전월까지_6개월_미만이면_TERM_TOO_SHORT() throws Exception {
        visaProfile("E-9", "PHL", today.plusMonths(6).plusDays(1));
        transactions(6, true);
        incomeRecord(2025, 30_000_000, "DOCUMENT", "VERIFIED");

        solGlobal()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility.resultLabel").value("5개 중 5개 충족"))
                .andExpect(jsonPath("$.limitEstimate.estimatedLimit").doesNotExist())
                .andExpect(jsonPath("$.limitEstimate.reasonCode").value("TERM_TOO_SHORT"))
                .andExpect(jsonPath("$.limitEstimate.maxTermMonths")
                        .value(lessThan(6)));
    }

    @Test
    void 만료일이_없으면_stay_미충족() throws Exception {
        visaProfile("E-9", "PHL", null);
        transactions(6, true);
        incomeRecord(2025, 30_000_000, "DOCUMENT", "VERIFIED");

        expectUnmet("stay", "체류 만료일 정보 없음");
    }

    @Test
    void 연소득이_2000만원_미만이면_income_미충족() throws Exception {
        visaProfile("E-9", "PHL", today.plusYears(2));
        transactions(6, true);
        incomeRecord(2025, 19_999_999, "DOCUMENT", "VERIFIED");

        expectUnmet("income", "현재 연 1,999만 원(2025년 소득 확인 기준)");
    }

    @Test
    void 연소득은_가장_최근_VERIFIED_연도를_쓴다() throws Exception {
        eligibleData();
        incomeRecord(2024, 25_000_000, "DOCUMENT", "VERIFIED");
        incomeRecord(2026, 99_000_000, "USER_DECLARED", "DECLARED");

        solGlobal()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limitEstimate.annualIncome.amount").value(30_000_000))
                .andExpect(jsonPath("$.limitEstimate.incomeYear").value(2025));
    }

    @Test
    void VERIFIED_소득이_없으면_현_직장_계약_연봉을_쓴다() throws Exception {
        visaProfile("E-9", "PHL", today.plusYears(5));
        transactions(6, true);
        incomeRecord(2025, 50_000_000, "USER_DECLARED", "DECLARED");
        employment("2020-01-01", "2022-12-31", 40_000_000);
        employment("2023-01-02", null, 28_000_000);

        solGlobal()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility.conditions[4].met").value(true))
                .andExpect(jsonPath("$.eligibility.conditions[4].desc").value("현재 연 2,800만 원(고용계약 연봉 기준)"))
                .andExpect(jsonPath("$.limitEstimate.incomeSource").value("CONTRACT_SALARY"))
                .andExpect(jsonPath("$.limitEstimate.incomeYear").doesNotExist())
                .andExpect(jsonPath("$.limitEstimate.annualIncome.amount").value(28_000_000))
                .andExpect(jsonPath("$.limitEstimate.estimatedLimit.amount").value(22_400_000));
    }

    @Test
    void 소득_정보가_전혀_없으면_income_미충족_NONE() throws Exception {
        visaProfile("E-9", "PHL", today.plusYears(2));
        transactions(6, true);

        expectUnmet("income", "연소득 정보 없음")
                .andExpect(jsonPath("$.limitEstimate.incomeSource").value("NONE"))
                .andExpect(jsonPath("$.limitEstimate.annualIncome").doesNotExist())
                .andExpect(jsonPath("$.limitEstimate.byIncomeRatio").doesNotExist());
    }

    @Test
    void 체류_프로필이_없으면_422_NO_VISA_PROFILE() throws Exception {
        solGlobal()
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("NO_VISA_PROFILE"));
    }

    @Test
    void 마이데이터_적재가_없으면_422_NO_SUCCEEDED_BATCH() throws Exception {
        visaProfile("E-9", "PHL", today.plusYears(2));

        solGlobal()
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("NO_SUCCEEDED_BATCH"));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    /** E-9·PHL·만료 2년 뒤·급여 6개월·연소득 3,000만 원(2025 VERIFIED): 다섯 조건 모두 충족. */
    private void eligibleData() {
        visaProfile("E-9", "PHL", today.plusYears(2));
        transactions(6, true);
        incomeRecord(2025, 30_000_000, "DOCUMENT", "VERIFIED");
    }

    private ResultActions expectUnmet(String key, String descContains) throws Exception {
        return solGlobal()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibility.resultLabel").value("5개 중 4개 충족"))
                .andExpect(jsonPath("$.eligibility.conditions[?(@.key == '" + key + "')].met").value(false))
                .andExpect(jsonPath("$.eligibility.conditions[?(@.key == '" + key + "')].desc")
                        .value(contains(containsString(descContains))))
                .andExpect(jsonPath("$.eligibility.conditions[?(@.met == false)]")
                        .value(hasSize(1)))
                .andExpect(jsonPath("$.limitEstimate.estimatedLimit").doesNotExist())
                .andExpect(jsonPath("$.limitEstimate.reasonCode").value("NOT_ELIGIBLE"));
    }

    private ResultActions solGlobal() throws Exception {
        return mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private void visaProfile(String visaCode, String nationalityCode, LocalDate stayExpiresOn) {
        jdbc.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, nationality_code,
                    current_stay_started_on, prior_stay_months)
                VALUES (?, (SELECT id FROM visa_types WHERE code = ?), ?, ?, ?, 0)""",
                userId, visaCode, stayExpiresOn, nationalityCode, today.minusYears(2));
    }

    /**
     * SUCCEEDED Mock 배치(2026-01-01~2026-10-31)와 신한은행 입출금 계좌. 끝 달(10월)부터 거꾸로 salaryMonths 개월
     * 급여 입금(그 앞 달은 급여 없이 기타 입금). batchId 를 돌려준다.
     */
    private long transactions(int salaryMonths, boolean shinhanSalaryAccount) {
        jdbc.update("INSERT INTO ingestion_batches (user_id, source_type, request_key, status, coverage_from, "
                + "coverage_to, started_at, finished_at) VALUES (?, 'MOCK', 'test-sol', 'SUCCEEDED', '2026-01-01', "
                + "'2026-10-31', NOW(3), NOW(3))", userId);
        long batchId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO financial_accounts (user_id, institution_id, batch_id, external_account_key, "
                + "account_no_masked, account_type, is_salary_account) VALUES (?, (SELECT id FROM institutions "
                + "WHERE institution_code = 'SHINHAN_BANK'), ?, 'test-demand', '110-***-**0000', 'DEMAND', ?)",
                userId, batchId, shinhanSalaryAccount);
        long accountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (int month = 1; month <= 10; month++) {
            boolean salary = month > 10 - salaryMonths;
            String date = String.format("2026-%02d-05", month);
            jdbc.update("INSERT INTO financial_transactions (user_id, account_id, batch_id, external_txn_key, "
                    + "transacted_at, booked_date, direction, amount, currency, category_code, transaction_kind) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'INCOME', 2500000, 'KRW', ?, 'NORMAL')",
                    userId, accountId, batchId, "in-" + month, date + " 01:00:00", date,
                    salary ? "SALARY" : "OTHER_INCOME");
        }
        return batchId;
    }

    private void incomeRecord(int year, long amount, String source, String status) {
        jdbc.update("INSERT INTO income_records (user_id, income_year, annual_amount, source, verification_status) "
                + "VALUES (?, ?, ?, ?, ?)", userId, year, amount, source, status);
    }

    private void employment(String startedOn, String endedOn, long salary) {
        jdbc.update("INSERT INTO employment_histories (user_id, employer_name, industry_code, started_on, ended_on, "
                + "contract_annual_salary) VALUES (?, 'TEST', 'MANUFACTURING', ?, ?, ?)",
                userId, startedOn, endedOn, salary);
    }

    private void loan(long batchId, String key, long balance, String loanStatus) {
        jdbc.update("""
                INSERT INTO loans (user_id, institution_id, batch_id, external_loan_key, principal,
                    outstanding_balance, started_on, status)
                VALUES (?, (SELECT id FROM institutions WHERE institution_code = 'SHINHAN_BANK'), ?, ?, ?, ?,
                    '2025-01-01', ?)""",
                userId, batchId, key, balance, balance, loanStatus);
    }
}
