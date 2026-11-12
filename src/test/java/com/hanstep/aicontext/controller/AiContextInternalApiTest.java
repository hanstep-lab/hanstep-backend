package com.hanstep.aicontext.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.commitment.domain.CommitmentType;
import com.hanstep.commitment.dto.CommitmentSaveRequest;
import com.hanstep.commitment.service.CommitmentService;
import com.hanstep.common.dto.Money;
import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.finance.client.AiFinanceClient;
import com.hanstep.finance.client.AiFinanceClient.AnalyzeResponse;
import com.hanstep.finance.client.AiFinanceClient.DataQuality;
import com.hanstep.finance.client.AiFinanceClient.VariableExpense;
import com.hanstep.finance.service.FinanceAnalysisService;
import com.hanstep.goal.domain.GoalType;
import com.hanstep.goal.dto.GoalSaveRequest;
import com.hanstep.goal.service.GoalService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * INT-06. 테스트 사용자에 데모 Minh 와 같은 값(분석·필수 확보액·목표)을 기존 서비스로 넣고, INT-06 이 FIN-06·CMT-01·GOL-01 과
 * 같은 값을 주는지 본다. INT-01(AI)은 가짜로 바꿔 끼움. 테스트마다 롤백.
 */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class AiContextInternalApiTest {

    private static final String URL = "/internal/v1/ai-context/users/%d/finance";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private FinanceAnalysisService financeAnalysisService;

    @Autowired
    private CommitmentService commitmentService;

    @Autowired
    private GoalService goalService;

    @MockitoBean
    private AiFinanceClient aiFinanceClient;

    private long userId;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES ('test-ai-context', 'TEST')");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = 'test-ai-context'", Long.class);
    }

    @Test
    void 토큰이_없거나_틀리면_401() throws Exception {
        mockMvc.perform(get(URL.formatted(userId))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL.formatted(userId)).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY + "x"))
                .andExpect(status().isUnauthorized());
        // 앱 JWT 로는 내부 API 를 부를 수 없다
        mockMvc.perform(get(URL.formatted(userId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(userId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 없는_사용자는_404() throws Exception {
        mockMvc.perform(get(URL.formatted(999_999_999L)).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 분석이_없으면_latestAnalysis_null과_빈_값() throws Exception {
        mockMvc.perform(get(URL.formatted(userId)).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.latestAnalysis").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.commitments.deductibleTotal.amount").value(0))
                .andExpect(jsonPath("$.goals.length()").value(0));
    }

    @Test
    void 민_데이터는_FIN06_CMT01_GOL01과_같은_값이고_계좌_정보는_없다() throws Exception {
        minhAnalysis();
        commitmentService.saveCommitments(userId, new CommitmentSaveRequest(List.of(
                new CommitmentSaveRequest.Item(CommitmentType.RENT, "Rent", Money.krw(520_000), null, null),
                new CommitmentSaveRequest.Item(CommitmentType.REMITTANCE, "Mom", Money.krw(700_000), null, null),
                new CommitmentSaveRequest.Item(CommitmentType.VARIABLE_EXPENSE, null, Money.krw(450_000), null, null))));
        goalService.saveGoals(userId, new GoalSaveRequest(List.of(
                new GoalSaveRequest.Item(GoalType.LUMP_SUM, Money.krw(6_000_000), LocalDate.of(2027, 9, 30)))));

        String raw = mockMvc.perform(get(URL.formatted(userId))
                        .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.latestAnalysis.periodFrom").value("2025-11-01"))
                .andExpect(jsonPath("$.latestAnalysis.periodTo").value("2026-10-31"))
                .andExpect(jsonPath("$.latestAnalysis.avgMonthlyIncome.amount").value(2_350_000))
                .andExpect(jsonPath("$.latestAnalysis.fixedLiving.amount").value(520_000))
                .andExpect(jsonPath("$.latestAnalysis.remittance.amount").value(700_000))
                .andExpect(jsonPath("$.latestAnalysis.variableExpense.avg.amount").value(454_683))
                .andExpect(jsonPath("$.latestAnalysis.variableExpense.min.amount").value(410_600))
                .andExpect(jsonPath("$.latestAnalysis.variableExpense.max.amount").value(512_400))
                .andExpect(jsonPath("$.latestAnalysis.spare.amount").value(675_317))
                .andExpect(jsonPath("$.latestAnalysis.dataQuality.observedMonthCount").value(12))
                .andExpect(jsonPath("$.commitments.fixedLiving.amount").value(520_000))
                .andExpect(jsonPath("$.commitments.remittance.amount").value(700_000))
                .andExpect(jsonPath("$.commitments.variableExpense.amount").value(450_000))
                .andExpect(jsonPath("$.commitments.mandatorySaving.amount").value(0))
                .andExpect(jsonPath("$.commitments.deductibleTotal.amount").value(1_670_000))
                .andExpect(jsonPath("$.goals[0].goalType").value("LUMP_SUM"))
                .andExpect(jsonPath("$.goals[0].targetAmount.amount").value(6_000_000))
                .andExpect(jsonPath("$.goals[0].targetDate").value("2027-09-30"))
                .andReturn().getResponse().getContentAsString();
        JsonNode context = MAPPER.readTree(raw);

        // 새로 계산하지 않고 앱 API 와 같은 값
        String bearer = "Bearer " + jwtTokenProvider.issue(userId);
        assertThat(context.path("latestAnalysis")).isEqualTo(app("/api/v1/finance/analyses/latest", bearer));
        assertThat(context.path("commitments")).isEqualTo(app("/api/v1/commitments", bearer).path("totals"));
        assertThat(context.path("goals")).isEqualTo(app("/api/v1/goals", bearer).path("goals"));

        // LLM 에 넘어가는 값이라 계좌·잔액·계좌번호·거래처·생년월일·등록번호가 없어야 한다
        assertThat(raw).doesNotContainIgnoringCase("account").doesNotContainIgnoringCase("balance")
                .doesNotContain("110-").doesNotContainIgnoringCase("counterparty")
                .doesNotContainIgnoringCase("birth").doesNotContainIgnoringCase("registration")
                .doesNotContain("TEST");
    }

    private void minhAnalysis() {
        jdbc.update("INSERT INTO ingestion_batches (user_id, source_type, request_key, status, coverage_from, "
                + "coverage_to, started_at, finished_at) VALUES (?, 'MOCK', 'test-ctx', 'SUCCEEDED', '2025-11-01', "
                + "'2026-10-31', NOW(3), NOW(3))", userId);
        long batchId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO financial_accounts (user_id, institution_id, batch_id, external_account_key, "
                + "account_no_masked, account_type, balance) VALUES (?, (SELECT id FROM institutions "
                + "WHERE institution_code = 'SHINHAN_BANK'), ?, 'test-ctx', '110-***-**0000', 'DEMAND', 1234567)",
                userId, batchId);
        long accountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO financial_transactions (user_id, account_id, batch_id, external_txn_key, transacted_at, "
                + "booked_date, direction, amount, currency, category_code, transaction_kind, counterparty_name) "
                + "VALUES (?, ?, ?, 'salary', '2026-09-05 01:00:00', '2026-09-05', 'INCOME', 2350000, 'KRW', 'SALARY', "
                + "'NORMAL', 'ACME CORP')", userId, accountId, batchId);
        when(aiFinanceClient.analyze(any())).thenReturn(new AnalyzeResponse(Money.krw(2_350_000), Money.krw(520_000),
                Money.krw(700_000), new VariableExpense(Money.krw(454_683), Money.krw(410_600), Money.krw(512_400)),
                Money.krw(675_317), new DataQuality(12, List.of(), List.of(), List.of()), List.of()));
        financeAnalysisService.analyze(userId, null);
    }

    private JsonNode app(String path, String bearer) throws Exception {
        return MAPPER.readTree(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
