package com.hanstep.credit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.credit.client.AiAltCreditClient;
import com.hanstep.credit.client.AiAltCreditClient.AssessRequest;
import com.hanstep.credit.client.AiAltCreditClient.AssessResponse;
import com.hanstep.credit.domain.AltCreditDecision;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reyes 시드(03_hanstep_demo_seed.sql)와 Mock 원천(MYD-02)으로 대안신용 입력이 기대대로 모이는지. AI 계산은 가짜.
 * 데모 사용자에 동의·MYD-02 적재를 하지만 테스트 트랜잭션 안이라 끝나면 롤백된다(이미 적재된 DB 여도 결과가 같다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AltCreditReyesSeedTest {

    // 03 시드의 Reyes 체류 프로필
    private static final LocalDate STAY_STARTED_ON = LocalDate.of(2022, 12, 5);
    private static final LocalDate STAY_EXPIRES_ON = LocalDate.of(2027, 10, 4);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private AiAltCreditClient aiAltCreditClient;

    private long reyesId;
    private String bearer;

    @BeforeEach
    void setUp() {
        reyesId = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = 'demo-e9-ph-reyes'", Long.class);
        bearer = "Bearer " + jwtTokenProvider.issue(reyesId);
        when(aiAltCreditClient.assess(any())).thenReturn(new AssessResponse("alt-credit-v9", true, 0.01, 814, null,
                LocalDate.of(2026, 11, 11), 0, 0L, 0L, null, AltCreditDecision.NOT_ELIGIBLE, "NO_INCOME", List.of(),
                "모의 사전진단이며 실제 대출 승인이나 신용점수가 아닙니다"));
    }

    @Test
    void 시드_청구_납부는_3종_6개월_18행이고_모두_기한_안에_냈다() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.bill_type, COUNT(*) AS n, MIN(b.billing_month) AS first_month,
                       MAX(b.billing_month) AS last_month, SUM(b.paid_on IS NULL OR b.paid_on > b.due_on) AS late,
                       SUM(NOT ib.is_mock) AS not_mock
                FROM mydata_bill_payments b JOIN ingestion_batches ib ON ib.id = b.ingestion_batch_id
                WHERE b.user_id = ? GROUP BY b.bill_type ORDER BY b.bill_type""", reyesId);

        assertThat(rows).extracting(row -> row.get("bill_type"))
                .containsExactly("TELECOM", "HEALTH_INSURANCE", "UTILITY");
        assertThat(rows).allSatisfy(row -> {
            assertThat(((Number) row.get("n")).intValue()).isEqualTo(6);
            assertThat(row.get("first_month").toString()).isEqualTo("2026-05-01");
            assertThat(row.get("last_month").toString()).isEqualTo("2026-10-01");
            assertThat(((Number) row.get("late")).intValue()).isZero();
            assertThat(((Number) row.get("not_mock")).intValue()).isZero();
        });
        // 청약(INSTALLMENT) 계좌는 시드에도 Mock 원천에도 없다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_accounts WHERE user_id = ? "
                + "AND account_type = 'INSTALLMENT'", Integer.class, reyesId)).isZero();
    }

    @Test
    void MYD02_적재_후_입력이_페르소나_값으로_모인다() throws Exception {
        grant("MYDATA");
        grant("MYDATA_TERMS");
        grant("CREDIT_EVALUATION");
        mockMvc.perform(post("/api/v1/mydata/connections")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", "test-alt-credit-reyes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutionIds\":[" + institutionId("SHINHAN_BANK") + ","
                                + institutionId("SHINHAN_CARD") + "],\"scopes\":[\"ACCOUNT_BALANCE\",\"TRANSACTIONS\","
                                + "\"CARD_USAGE\"],\"lookbackMonths\":12}"))
                .andExpect(status().is2xxSuccessful());

        // 테스트 트랜잭션 안이라 MYD-02 배치의 SUCCEEDED(JPA 변경)가 아직 DB 에 없다. 내보내지 않으면 JDBC 로 읽는
        // "최근 SUCCEEDED 배치"가 시드의 청구 납부 배치(2026-05~10)가 된다(실제 요청은 커밋된 뒤라 생기지 않음)
        entityManager.flush();
        LocalDate today = LocalDate.now(KstDates.KST);
        int stayMonths = Math.toIntExact(Period.between(STAY_STARTED_ON, today).toTotalMonths()); // 이전 체류 0
        int toExpiry = today.isBefore(STAY_EXPIRES_ON)
                ? Math.toIntExact(Period.between(today, STAY_EXPIRES_ON).toTotalMonths()) : 0;

        mockMvc.perform(get("/api/v1/credit/alt-assessment").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs.stayDurationMonths").value(stayMonths))
                .andExpect(jsonPath("$.inputs.monthsToExpiry").value(toExpiry))
                .andExpect(jsonPath("$.inputs.workPermitted").value(true))
                .andExpect(jsonPath("$.inputs.monthlyIncome").value(2_350_000))
                .andExpect(jsonPath("$.inputs.salaryMonths").value(12))
                .andExpect(jsonPath("$.inputs.shinhanPayroll").value(true))
                .andExpect(jsonPath("$.inputs.hasSubscription").value(false))
                .andExpect(jsonPath("$.inputs.isRefinance").value(false))
                .andExpect(jsonPath("$.inputs.telLate6m").value(0))
                .andExpect(jsonPath("$.inputs.utilLate6m").value(0))
                .andExpect(jsonPath("$.inputs.remitRatio").value(0.2979))
                .andExpect(jsonPath("$.inputs.remitMonths6m").value(6))
                .andExpect(jsonPath("$.inputs.periodFrom").value("2025-11-01"))
                .andExpect(jsonPath("$.inputs.periodTo").value("2026-10-31"))
                .andExpect(jsonPath("$.inputs.recentFrom").value("2026-05-01"))
                // 표시용: 814점 → 5구간, 요인 모두 GOOD, 점검 모두 충족(만료 점검은 날짜가 지나면 바뀌어 계산값과 비교)
                .andExpect(jsonPath("$.scoreBand.index").value(5))
                .andExpect(jsonPath("$.scoreBand.min").value(801))
                .andExpect(jsonPath("$.scoreBand.max").value(1000))
                .andExpect(jsonPath("$.factors[*].level").value(contains("GOOD", "GOOD", "GOOD", "GOOD", "GOOD")))
                .andExpect(jsonPath("$.factors[*].value").value(contains(0, 0, 6, 0.2979, stayMonths)))
                .andExpect(jsonPath("$.eligibilityChecks[*].met").value(contains(true, true, toExpiry > 6)))
                .andExpect(jsonPath("$.eligibilityChecks[*].value").value(contains(true, 12, toExpiry)));

        ArgumentCaptor<AssessRequest> captor = ArgumentCaptor.forClass(AssessRequest.class);
        verify(aiAltCreditClient).assess(captor.capture());
        // 월 송금 70만(어머니 50만 + 본인 20만) ÷ 급여 235만 = 0.29787… → 0.2979
        assertThat(captor.getValue()).isEqualTo(new AssessRequest(true, stayMonths, toExpiry, 2_350_000, true, 12,
                true, false, false, 0, 0, new BigDecimal("0.2979"), 6));
    }

    private void grant(String consentType) {
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, policy_version, status, granted_at) "
                + "VALUES (?, ?, 'test', 'GRANTED', NOW(3))", reyesId, consentType);
    }

    private long institutionId(String code) {
        return jdbc.queryForObject("SELECT id FROM institutions WHERE institution_code = ?", Long.class, code);
    }
}
