package com.hanstep.credit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.credit.client.AiAltCreditClient;
import com.hanstep.credit.client.AiAltCreditClient.AiCallException;
import com.hanstep.credit.client.AiAltCreditClient.AiValidationException;
import com.hanstep.credit.client.AiAltCreditClient.AssessRequest;
import com.hanstep.credit.client.AiAltCreditClient.AssessResponse;
import com.hanstep.credit.domain.AltCreditDecision;
import com.hanstep.credit.dto.AltCreditAssessmentResponse.RateBreakdown;
import com.hanstep.support.TestUsers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대안신용 모의 사전진단 API. 테스트 사용자·체류 프로필·Mock 거래·청구 납부를 직접 넣고, AI 계산은 가짜로 바꿔 끼운다.
 * 테스트마다 롤백. Reyes 시드로 입력이 모이는지는 AltCreditReyesSeedTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AltCreditAssessmentApiTest {

    private static final String URL = "/api/v1/credit/alt-assessment";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AiAltCreditClient aiAltCreditClient;

    private LocalDate today;
    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(KstDates.KST);
        userId = TestUsers.create(jdbc, "test-alt-credit", "TEST ALT CREDIT");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        when(aiAltCreditClient.assess(any())).thenReturn(sample());
    }

    @Test
    void 입력을_모아_AI에_보내고_응답에_inputs와_mockFields를_붙인다() throws Exception {
        grant("CREDIT_EVALUATION");
        visaProfile("E-9", today.minusMonths(30).minusDays(3), 12, today.plusMonths(20).plusDays(5));
        fullData();

        assess("")
                .andExpect(status().isOk())
                // AI 응답 그대로
                .andExpect(jsonPath("$.modelVersion").value("alt-credit-v9"))
                .andExpect(jsonPath("$.isMock").value(true))
                .andExpect(jsonPath("$.pd").value(0.0123))
                .andExpect(jsonPath("$.score").value(850))
                .andExpect(jsonPath("$.rateBreakdown.final").value(0.1134))
                .andExpect(jsonPath("$.rateBreakdown.base").value(0.0383))
                .andExpect(jsonPath("$.rateBaseDate").value("2026-11-11"))
                .andExpect(jsonPath("$.loanTermMonths").value(19))
                .andExpect(jsonPath("$.minLimit").value(5_000_000))
                .andExpect(jsonPath("$.maxLimit").value(7_000_000))
                .andExpect(jsonPath("$.maxLimitBinding").value("SIMPLE_DSR_40"))
                .andExpect(jsonPath("$.decision").value("ELIGIBLE"))
                .andExpect(jsonPath("$.reasonCode").doesNotExist())
                .andExpect(jsonPath("$.inputWarnings.length()").value(0))
                .andExpect(jsonPath("$.disclaimer").value("모의 사전진단이며 실제 대출 승인이나 신용점수가 아닙니다"))
                // 보낸 입력
                .andExpect(jsonPath("$.inputs.stayDurationMonths").value(42))
                .andExpect(jsonPath("$.inputs.monthsToExpiry").value(20))
                .andExpect(jsonPath("$.inputs.workPermitted").value(true))
                .andExpect(jsonPath("$.inputs.monthlyIncome").value(1_800_000))
                .andExpect(jsonPath("$.inputs.salaryMonths").value(4))
                .andExpect(jsonPath("$.inputs.shinhanPayroll").value(true))
                .andExpect(jsonPath("$.inputs.hasSubscription").value(true))
                .andExpect(jsonPath("$.inputs.isRefinance").value(false))
                .andExpect(jsonPath("$.inputs.telLate6m").value(2))
                .andExpect(jsonPath("$.inputs.utilLate6m").value(1))
                .andExpect(jsonPath("$.inputs.remitRatio").value(0.0625))
                .andExpect(jsonPath("$.inputs.remitMonths6m").value(3))
                .andExpect(jsonPath("$.inputs.asOfDate").value(today.toString()))
                .andExpect(jsonPath("$.inputs.periodFrom").value("2026-02-01"))
                .andExpect(jsonPath("$.inputs.periodTo").value("2026-09-30"))
                .andExpect(jsonPath("$.inputs.recentFrom").value("2026-04-01"))
                .andExpect(jsonPath("$.mockFields.length()").value(8))
                .andExpect(jsonPath("$.mockFields[0]").value("monthlyIncome"));

        assertThat(sentRequest()).isEqualTo(new AssessRequest(true, 42, 20, 1_800_000, true, 4, true, true, false,
                2, 1, new BigDecimal("0.0625"), 3));
        // 저장하지 않는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_assessments WHERE user_id = ?", Integer.class,
                userId)).isZero();
    }

    @Test
    void 대환은_요청_파라미터로_받는다() throws Exception {
        grant("CREDIT_EVALUATION");
        visaProfile("E-9", today.minusMonths(30), 0, today.plusMonths(20));
        fullData();

        assess("?isRefinance=true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs.isRefinance").value(true));
        assertThat(sentRequest().isRefinance()).isTrue();
    }

    @Test
    void 만료일이_없으면_null_송금이_없으면_null이고_근로_불가_자격은_false() throws Exception {
        grant("CREDIT_EVALUATION");
        visaProfile("D-2", today.minusMonths(10), null, null);
        long[] ids = batch("2026-09-01", "2026-10-31");
        txn(ids, "sal-09", "2026-09-25", "INCOME", 800_000, "SALARY", "NORMAL");

        assess("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs.stayDurationMonths").value(10))
                .andExpect(jsonPath("$.inputs.monthsToExpiry").doesNotExist())
                .andExpect(jsonPath("$.inputs.workPermitted").value(false))
                .andExpect(jsonPath("$.inputs.monthlyIncome").value(400_000))
                .andExpect(jsonPath("$.inputs.salaryMonths").value(0))
                .andExpect(jsonPath("$.inputs.shinhanPayroll").value(false))
                .andExpect(jsonPath("$.inputs.hasSubscription").value(false))
                .andExpect(jsonPath("$.inputs.remitRatio").value(0))
                .andExpect(jsonPath("$.inputs.remitMonths6m").doesNotExist())
                .andExpect(jsonPath("$.inputs.telLate6m").value(0));

        AssessRequest sent = sentRequest();
        assertThat(sent.monthsToExpiry()).isNull();
        assertThat(sent.remitMonths6m()).isNull();
    }

    @Test
    void 만료일이_지났으면_남은_개월은_0() throws Exception {
        grant("CREDIT_EVALUATION");
        visaProfile("E-9", today.minusMonths(30), 0, today.minusDays(1));
        fullData();

        assess("").andExpect(status().isOk()).andExpect(jsonPath("$.inputs.monthsToExpiry").value(0));
    }

    @Test
    void CREDIT_EVALUATION_동의가_없으면_403이고_AI를_부르지_않는다() throws Exception {
        visaProfile("E-9", today.minusMonths(30), 0, today.plusMonths(20));
        fullData();
        grant("MYDATA");

        assess("")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.details.consentType").value("CREDIT_EVALUATION"))
                .andExpect(jsonPath("$.requestId").exists());
        verify(aiAltCreditClient, never()).assess(any());
    }

    @Test
    void 동의를_철회했으면_403() throws Exception {
        grant("CREDIT_EVALUATION");
        jdbc.update("""
                INSERT INTO user_consents (user_id, consent_type, policy_version, status, granted_at, revoked_at)
                VALUES (?, 'CREDIT_EVALUATION', 'test', 'REVOKED', NOW(3), NOW(3))""", userId);
        visaProfile("E-9", today.minusMonths(30), 0, today.plusMonths(20));
        fullData();

        assess("")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        verify(aiAltCreditClient, never()).assess(any());
    }

    @Test
    void AI_호출이_실패하면_502() throws Exception {
        grant("CREDIT_EVALUATION");
        visaProfile("E-9", today.minusMonths(30), 0, today.plusMonths(20));
        fullData();
        when(aiAltCreditClient.assess(any())).thenThrow(new AiCallException("AI_UNREACHABLE", null));

        assess("")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"));
    }

    @Test
    void AI가_입력을_거부하면_422_INPUT_REJECTED와_필드_경로() throws Exception {
        grant("CREDIT_EVALUATION");
        visaProfile("E-9", today.minusMonths(3), 0, today.plusMonths(20));
        fullData();
        when(aiAltCreditClient.assess(any())).thenThrow(new AiValidationException(
                List.of(Map.of("field", "stayDurationMonths", "reason", "out_of_training_range")), null));

        assess("")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.details.reason").value("INPUT_REJECTED"))
                .andExpect(jsonPath("$.details.fields[0].field").value("stayDurationMonths"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("out_of_training_range"));
    }

    @Test
    void 체류_프로필이_없으면_422_NO_VISA_PROFILE() throws Exception {
        grant("CREDIT_EVALUATION");
        fullData();

        assess("")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("NO_VISA_PROFILE"));
        verify(aiAltCreditClient, never()).assess(any());
    }

    @Test
    void 체류_시작일이_없으면_422_NO_STAY_START_DATE() throws Exception {
        grant("CREDIT_EVALUATION");
        visaProfile("E-9", null, 0, today.plusMonths(20));
        fullData();

        assess("")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("NO_STAY_START_DATE"));
    }

    @Test
    void 마이데이터_적재가_없으면_422_NO_SUCCEEDED_BATCH() throws Exception {
        grant("CREDIT_EVALUATION");
        visaProfile("E-9", today.minusMonths(30), 0, today.plusMonths(20));

        assess("")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("NO_SUCCEEDED_BATCH"));
        verify(aiAltCreditClient, never()).assess(any());
    }

    @Test
    void 집계_기간에_거래가_없으면_422_NO_TRANSACTIONS() throws Exception {
        grant("CREDIT_EVALUATION");
        visaProfile("E-9", today.minusMonths(30), 0, today.plusMonths(20));
        batch("2026-09-01", "2026-10-31");

        assess("")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("NO_TRANSACTIONS"));
    }

    @Test
    void 수집_기간에_꽉_찬_달이_없으면_422_NO_FULL_MONTH() throws Exception {
        grant("CREDIT_EVALUATION");
        visaProfile("E-9", today.minusMonths(30), 0, today.plusMonths(20));
        batch("2026-10-05", "2026-10-25");

        assess("")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("NO_FULL_MONTH"));
    }

    @Test
    void isRefinance가_불리언이_아니면_400() throws Exception {
        assess("?isRefinance=maybe")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    /**
     * 수집 2026-01-15~10-20 → 집계 2026-02~09(8개월), 최근 6개월 2026-04~09.
     * 소득: 급여 200만 × 7(5월 없음) + 기타 40만(5월) = 1,440만 → 월 180만. 본인 이체·기간 밖 입금은 빼고 센다.
     * 연속 급여: 9·8·7·6월 = 4(5월에서 끊김). 송금 30만 × 3(4·6·9월) → 비율 90만 ÷ 1,440만 = 0.0625, 송금한 달 3.
     * 통신비 연체: 4월(늦게 냄)·7월(기한 지난 미납) = 2. 3월은 6개월 밖이라 안 셈.
     * 건보·공과금 연체: 6월에 둘 다 늦음 → 1. 8월 기한 당일 납부, 9월 기한 전 미납은 연체 아님.
     * 계좌: 신한은행 급여 계좌 + 적금(INSTALLMENT) 계좌.
     */
    private void fullData() {
        long[] ids = batch("2026-01-15", "2026-10-20");
        for (String month : List.of("02", "03", "04", "06", "07", "08", "09")) {
            txn(ids, "sal-" + month, "2026-" + month + "-05", "INCOME", 2_000_000, "SALARY", "NORMAL");
        }
        txn(ids, "other-05", "2026-05-20", "INCOME", 400_000, "OTHER_INCOME", "NORMAL");
        txn(ids, "own-06", "2026-06-11", "INCOME", 5_000_000, "TRANSFER", "OWN_TRANSFER");
        txn(ids, "before", "2026-01-20", "INCOME", 9_999_999, "SALARY", "NORMAL");
        for (String month : List.of("04", "06", "09")) {
            txn(ids, "remit-" + month, "2026-" + month + "-10", "EXPENSE", 300_000, "REMITTANCE", "NORMAL");
        }
        txn(ids, "rent-07", "2026-07-25", "EXPENSE", 400_000, "HOUSING", "NORMAL");
        jdbc.update("UPDATE financial_accounts SET is_salary_account = TRUE WHERE id = ?", ids[1]);
        jdbc.update("""
                INSERT INTO financial_accounts (user_id, institution_id, batch_id, external_account_key,
                    account_no_masked, account_type)
                VALUES (?, (SELECT id FROM institutions WHERE institution_code = 'SHINHAN_BANK'), ?, 'test-installment',
                    '110-***-**0001', 'INSTALLMENT')""", userId, ids[0]);

        bill(ids[0], "MOCK_TELECOM", "TELECOM", "2026-03-01", "2026-03-21", "2026-03-25");
        bill(ids[0], "MOCK_TELECOM", "TELECOM", "2026-04-01", "2026-04-21", "2026-04-23");
        bill(ids[0], "MOCK_TELECOM", "TELECOM", "2026-05-01", "2026-05-21", "2026-05-20");
        bill(ids[0], "MOCK_TELECOM", "TELECOM", "2026-07-01", "2026-07-21", null);
        bill(ids[0], "MOCK_NHIS", "HEALTH_INSURANCE", "2026-06-01", "2026-06-10", "2026-06-15");
        bill(ids[0], "MOCK_UTILITY", "UTILITY", "2026-06-01", "2026-06-25", "2026-06-28");
        bill(ids[0], "MOCK_UTILITY", "UTILITY", "2026-08-01", "2026-08-25", "2026-08-25");
        bill(ids[0], "MOCK_NHIS", "HEALTH_INSURANCE", "2026-09-01", today.plusDays(10).toString(), null);
    }

    private ResultActions assess(String query) throws Exception {
        return mockMvc.perform(get(URL + query).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private AssessRequest sentRequest() {
        ArgumentCaptor<AssessRequest> captor = ArgumentCaptor.forClass(AssessRequest.class);
        verify(aiAltCreditClient).assess(captor.capture());
        return captor.getValue();
    }

    private static AssessResponse sample() {
        return new AssessResponse("alt-credit-v9", true, 0.0123, 850,
                new RateBreakdown(0.0383, 0.0055, 0.0061, 0.073547, 0.0851, 0.01, 0.1134),
                LocalDate.of(2026, 11, 11), 19, 5_000_000L, 7_000_000L, "SIMPLE_DSR_40", AltCreditDecision.ELIGIBLE,
                null, List.of(), "모의 사전진단이며 실제 대출 승인이나 신용점수가 아닙니다");
    }

    private void grant(String consentType) {
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, policy_version, status, granted_at) "
                + "VALUES (?, ?, 'test', 'GRANTED', NOW(3))", userId, consentType);
    }

    private void visaProfile(String visaCode, LocalDate stayStartedOn, Integer priorStayMonths,
                             LocalDate stayExpiresOn) {
        jdbc.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, current_stay_started_on,
                    prior_stay_months)
                VALUES (?, (SELECT id FROM visa_types WHERE code = ?), ?, ?, ?)""",
                userId, visaCode, stayExpiresOn, stayStartedOn, priorStayMonths);
    }

    /** SUCCEEDED Mock 배치와 신한은행 입출금 계좌. [batchId, accountId] */
    private long[] batch(String coverageFrom, String coverageTo) {
        jdbc.update("INSERT INTO ingestion_batches (user_id, source_type, request_key, status, coverage_from, "
                + "coverage_to, started_at, finished_at) VALUES (?, 'MOCK', 'test-alt', 'SUCCEEDED', ?, ?, NOW(3), "
                + "NOW(3))", userId, coverageFrom, coverageTo);
        long batchId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO financial_accounts (user_id, institution_id, batch_id, external_account_key, "
                + "account_no_masked, account_type) VALUES (?, (SELECT id FROM institutions "
                + "WHERE institution_code = 'SHINHAN_BANK'), ?, 'test-demand', '110-***-**0000', 'DEMAND')",
                userId, batchId);
        long accountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new long[] {batchId, accountId};
    }

    private void txn(long[] ids, String key, String date, String direction, long amount, String category,
                     String kind) {
        jdbc.update("INSERT INTO financial_transactions (user_id, account_id, batch_id, external_txn_key, "
                + "transacted_at, booked_date, direction, amount, currency, category_code, transaction_kind) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'KRW', ?, ?)",
                userId, ids[1], ids[0], key, date + " 01:00:00", date, direction, amount, category, kind);
    }

    private void bill(long batchId, String institutionCode, String billType, String billingMonth, String dueOn,
                      String paidOn) {
        jdbc.update("""
                INSERT INTO mydata_bill_payments (user_id, institution_id, bill_type, billing_month, due_on, paid_on,
                    amount, ingestion_batch_id)
                VALUES (?, (SELECT id FROM institutions WHERE institution_code = ?), ?, ?, ?, ?, 50000, ?)""",
                userId, institutionCode, billType, billingMonth, dueOn, paidOn, batchId);
    }
}
