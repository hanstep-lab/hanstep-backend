package com.hanstep.credit.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.finance.client.AiFinanceClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reyes 시드(03_hanstep_demo_seed.sql)와 Mock 원천(MYD-02)으로 SOL 글로벌론 조건·모의 한도가 기대대로 나오는지.
 * 동의·MYD-02 적재는 테스트 트랜잭션 안이라 끝나면 롤백된다. CREDIT_EVALUATION 동의는 넣지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SolGlobalLoanReyesSeedTest {

    /** MYD-02 가 적재 뒤 부르는 금융 분석(INT-01). 로컬 AI 서버로 실제 요청이 나가지 않게 바꿔 끼운다(#149). */
    @MockitoBean
    private AiFinanceClient aiFinanceClient;

    // 03 시드: E-9, PHL, 만료 2027-10-04, 2025 소득 32,100,000(VERIFIED). 대출 데이터 없음
    private static final LocalDate STAY_EXPIRES_ON = LocalDate.of(2027, 10, 4);
    private static final LocalDate LATEST_MATURITY_ON = LocalDate.of(2027, 9, 30);
    private static final long ANNUAL_INCOME = 32_100_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @PersistenceContext
    private EntityManager entityManager;

    private long reyesId;
    private String bearer;

    @BeforeEach
    void setUp() {
        reyesId = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = 'demo-e9-ph-reyes'", Long.class);
        bearer = "Bearer " + jwtTokenProvider.issue(reyesId);
        // 기존 단정은 한국어 사용자 기준(Reyes 시드는 en). 영어는 Reyes_시드_언어_en 이면_영어_문구
        jdbc.update("UPDATE users SET preferred_language = 'ko' WHERE id = ?", reyesId);
    }

    @Test
    void Reyes_시드_언어_en_이면_영어_문구() throws Exception {
        jdbc.update("UPDATE users SET preferred_language = 'en' WHERE id = ?", reyesId);
        loadMydata();

        mockMvc.perform(get("/api/v1/credit/loans/sol-global").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("SOL Global Loan"))
                .andExpect(jsonPath("$.eligibility.conditions[0].desc").value("Employed 3+ months · Shinhan Bank "
                        + "payroll for the last 1+ month · Currently 12 months of salary deposits in a row, Shinhan "
                        + "payroll account: yes"))
                .andExpect(jsonPath("$.eligibility.conditions[2].desc").value(
                        "Vietnam·Indonesia·Cambodia·Kazakhstan·Uzbekistan·Philippines·Nepal · Current: Philippines"))
                .andExpect(jsonPath("$.eligibility.conditions[4].desc")
                        .value("Currently KRW 32,100,000 a year (based on 2025 income records)"));
    }

    @Test
    void MYD02_적재_후_Reyes는_5개_조건을_모두_충족하고_모의_한도가_나온다() throws Exception {
        loadMydata();
        LocalDate today = LocalDate.now(KstDates.KST);
        int toExpiry = today.isBefore(STAY_EXPIRES_ON)
                ? Math.toIntExact(Period.between(today, STAY_EXPIRES_ON).toTotalMonths()) : 0;
        int maxTerm = today.isAfter(LATEST_MATURITY_ON) ? 0
                : Math.min(36, Math.toIntExact(Period.between(today, LATEST_MATURITY_ON).toTotalMonths()));
        // 2026-11-17 기준: 10개월 → 32,100,000 × 0.4 × 10 ÷ 12 = 10,700,000 (< 25,680,000)
        long byRepayment = BigDecimal.valueOf(ANNUAL_INCOME).multiply(new BigDecimal("0.4"))
                .multiply(BigDecimal.valueOf(maxTerm)).divide(BigDecimal.valueOf(12), 0, RoundingMode.DOWN)
                .longValueExact();
        long limit = Math.min(Math.min(50_000_000L, 25_680_000L), byRepayment) / 10_000 * 10_000;
        boolean stayMet = STAY_EXPIRES_ON.isAfter(today.plusMonths(6));

        ResultActions result = mockMvc.perform(get("/api/v1/credit/loans/sol-global")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isMock").value(true))
                .andExpect(jsonPath("$.eligibility.conditions[*].key")
                        .value(contains("salary", "visa", "nationality", "stay", "income")))
                .andExpect(jsonPath("$.eligibility.conditions[0].desc").value("재직기간 3개월 이상 · 신한은행 급여이체 "
                        + "최근 1개월 이상 · 현재 급여 입금 12개월 연속, 신한 급여 계좌 있음"))
                .andExpect(jsonPath("$.eligibility.conditions[1].desc").value("E-9·E-7·F-2·F-5 중 하나 · 현재 E-9"))
                .andExpect(jsonPath("$.eligibility.conditions[2].desc").value(
                        "베트남·인도네시아·캄보디아·카자흐스탄·우즈베키스탄·필리핀·네팔 · 현재 필리핀"))
                .andExpect(jsonPath("$.eligibility.conditions[3].desc").value(toExpiry == 0
                        ? "2027.10.04 만료 · 1개월 미만 남음" : "2027.10.04 만료 · 약 " + toExpiry + "개월 남음"))
                .andExpect(jsonPath("$.eligibility.conditions[4].desc").value("현재 연 3,210만 원(2025년 소득 확인 기준)"))
                .andExpect(jsonPath("$.limitEstimate.annualIncome.amount").value(ANNUAL_INCOME))
                .andExpect(jsonPath("$.limitEstimate.incomeSource").value("INCOME_RECORD"))
                .andExpect(jsonPath("$.limitEstimate.incomeYear").value(2025))
                .andExpect(jsonPath("$.limitEstimate.latestMaturityOn").value("2027-09-30"))
                .andExpect(jsonPath("$.limitEstimate.maxTermMonths").value(maxTerm))
                .andExpect(jsonPath("$.limitEstimate.byIncomeRatio.amount").value(25_680_000))
                .andExpect(jsonPath("$.limitEstimate.byRepayment.amount").value(byRepayment))
                .andExpect(jsonPath("$.limitEstimate.existingLoans.amount").value(0))
                .andExpect(jsonPath("$.limitEstimate.dsrApplied").value(false));

        if (stayMet && maxTerm >= 6) {
            result.andExpect(jsonPath("$.eligibility.resultLabel").value("5개 중 5개 충족"))
                    .andExpect(jsonPath("$.eligibility.conditions[*].met").value(everyItem(is(true))))
                    .andExpect(jsonPath("$.limitEstimate.estimatedLimit.amount").value(limit))
                    .andExpect(jsonPath("$.limitEstimate.reasonCode").doesNotExist());
        } else {
            // 2027-04-05 이후에는 체류 만료까지 6개월 이하가 되어 stay 미충족
            result.andExpect(jsonPath("$.limitEstimate.estimatedLimit").doesNotExist());
        }
    }

    private void loadMydata() throws Exception {
        grant("MYDATA");
        grant("MYDATA_TERMS");
        mockMvc.perform(post("/api/v1/mydata/connections")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", "test-sol-global-reyes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutionIds\":[" + institutionId("SHINHAN_BANK") + ","
                                + institutionId("SHINHAN_CARD") + "],\"scopes\":[\"ACCOUNT_BALANCE\",\"TRANSACTIONS\","
                                + "\"CARD_USAGE\"],\"lookbackMonths\":12}"))
                .andExpect(status().is2xxSuccessful());
        // MYD-02 배치의 SUCCEEDED(JPA 변경)를 JDBC 로 읽으려면 내보내야 한다(AltCreditReyesSeedTest 와 같은 이유)
        entityManager.flush();
    }

    private void grant(String consentType) {
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, policy_version, status, granted_at) "
                + "VALUES (?, ?, 'test', 'GRANTED', NOW(3))", reyesId, consentType);
    }

    private long institutionId(String code) {
        return jdbc.queryForObject("SELECT id FROM institutions WHERE institution_code = ?", Long.class, code);
    }
}
