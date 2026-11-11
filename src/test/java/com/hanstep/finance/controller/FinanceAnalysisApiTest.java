package com.hanstep.finance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.dto.Money;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.finance.client.AiFinanceClient;
import com.hanstep.finance.client.AiFinanceClient.AiCallException;
import com.hanstep.finance.client.AiFinanceClient.AnalyzeRequest;
import com.hanstep.finance.client.AiFinanceClient.AnalyzeResponse;
import com.hanstep.finance.client.AiFinanceClient.DataQuality;
import com.hanstep.finance.client.AiFinanceClient.Excluded;
import com.hanstep.finance.client.AiFinanceClient.Transaction;
import com.hanstep.finance.client.AiFinanceClient.VariableExpense;
import com.hanstep.finance.service.FinanceAnalysisService;
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
 * FIN-05·06, ONB-04 balance. AI(INT-01)는 가짜로 바꿔 끼움(실제 호출 없음).
 * 데모 사용자에 기대지 않고 테스트 사용자·배치·거래를 직접 넣으며, 테스트마다 롤백.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FinanceAnalysisApiTest {

    private static final String ANALYSES = "/api/v1/finance/analyses";
    private static final String KST_TIME = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+]09:00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiFinanceClient aiFinanceClient;

    private Long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        userId = createUser("test-fin-a", "TEST FIN A");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    @Test
    void 분석을_실행하면_201과_저장_칸_매핑() throws Exception {
        long[] ids = succeededBatch(userId, "test-fin-1", "2026-08-15", "2026-10-31");
        long salary = txn(ids, "salary", "2026-09-05", "INCOME", 2350000, "SALARY", "NORMAL", "KRW");
        long remit = txn(ids, "remit", "2026-09-10", "EXPENSE", 500000, "REMITTANCE", "NORMAL", "KRW");
        long card = txn(ids, "card", "2026-10-03", "EXPENSE", 300000, "CARD_PAYMENT", "CARD_SETTLEMENT", "KRW");
        txn(ids, "before", "2026-08-20", "EXPENSE", 10000, "FOOD", "NORMAL", "KRW"); // 잘린 달(8월)
        txn(ids, "usd", "2026-09-15", "EXPENSE", 50, "FOOD", "NORMAL", "USD");
        when(aiFinanceClient.analyze(any())).thenReturn(result(List.of("UNKNOWN_CATEGORY:OTHER"),
                List.of(new Excluded(card, "CARD_SETTLEMENT"))));

        Long id = idOf(analyze(null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodFrom").value("2026-09-01"))
                .andExpect(jsonPath("$.periodTo").value("2026-10-31"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.avgMonthlyIncome.amount").value(2350000))
                .andExpect(jsonPath("$.fixedLiving.amount").value(520000))
                .andExpect(jsonPath("$.remittance.amount").value(700000))
                .andExpect(jsonPath("$.variableExpense.avg.amount").value(455000))
                .andExpect(jsonPath("$.variableExpense.min.amount").value(400000))
                .andExpect(jsonPath("$.variableExpense.max.amount").value(510000))
                .andExpect(jsonPath("$.spare.amount").value(675000))
                .andExpect(jsonPath("$.spare.currency").value("KRW"))
                .andExpect(jsonPath("$.dataQuality.observedMonthCount").value(2))
                .andExpect(jsonPath("$.dataQuality.warnings[0]").value("UNKNOWN_CATEGORY:OTHER"))
                .andExpect(jsonPath("$.dataQuality.partialMonths.length()").value(0))
                .andExpect(jsonPath("$.excludedCount").value(1))
                .andExpect(jsonPath("$.isMock").value(true))
                .andExpect(jsonPath("$.reused").value(false))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(KST_TIME))));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM financial_analyses WHERE id = ?", id);
        assertThat(row.get("user_id")).hasToString(userId.toString());
        assertThat(row.get("source_batch_id")).hasToString(String.valueOf(ids[0]));
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("engine_version")).isEqualTo(FinanceAnalysisService.ENGINE_VERSION);
        assertThat(row.get("source_txn_count")).hasToString("3");
        assertThat((String) row.get("source_fingerprint")).matches("[0-9a-f]{64}");
        assertThat((String) row.get("request_key")).isNotBlank();
        assertThat((BigDecimal) row.get("avg_monthly_income")).isEqualByComparingTo("2350000");
        assertThat((BigDecimal) row.get("avg_monthly_fixed")).isEqualByComparingTo("520000");
        assertThat((BigDecimal) row.get("avg_monthly_remittance")).isEqualByComparingTo("700000");
        assertThat((BigDecimal) row.get("avg_monthly_variable")).isEqualByComparingTo("455000");
        assertThat((BigDecimal) row.get("variable_monthly_min")).isEqualByComparingTo("400000");
        assertThat((BigDecimal) row.get("variable_monthly_max")).isEqualByComparingTo("510000");
        assertThat((BigDecimal) row.get("avg_monthly_expense")).isEqualByComparingTo("1675000"); // 고정+송금+변동
        assertThat((BigDecimal) row.get("avg_monthly_surplus")).isEqualByComparingTo("675000");
        assertThat(row.get("observed_month_count")).hasToString("2");
        assertThat(row.get("remittance_ratio")).isNull();
        assertThat(row.get("monthly_json")).isNull();
        assertThat(row.get("category_json")).isNull();
        assertThat(row.get("error_code")).isNull();
        assertThat(row.get("excluded_json").toString()).contains("\"transactionId\"").contains("CARD_SETTLEMENT");

        // AI 에 보낸 거래: KRW·기간 안만, 저장값 그대로
        AnalyzeRequest sent = sentRequest();
        assertThat(sent.userId()).isEqualTo(userId);
        assertThat(sent.periodFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(sent.periodTo()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(sent.transactions()).extracting(Transaction::id).containsExactly(salary, remit, card);
        assertThat(sent.transactions().get(2)).isEqualTo(new Transaction(card, LocalDate.of(2026, 10, 3), "EXPENSE",
                300000, "CARD_PAYMENT", "CARD_SETTLEMENT"));
    }

    @Test
    void 온보딩_최종_확인_balance는_최신_분석으로_채워진다() throws Exception {
        summary().andExpect(status().isOk()).andExpect(jsonPath("$.balance").isEmpty());

        long[] ids = succeededBatch(userId, "test-fin-2", "2026-09-01", "2026-10-31");
        txn(ids, "salary", "2026-09-05", "INCOME", 2350000, "SALARY", "NORMAL", "KRW");
        when(aiFinanceClient.analyze(any())).thenReturn(result(List.of(), List.of()));
        analyze(null).andExpect(status().isCreated());

        summary()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.avgMonthlyIncome.amount").value(2350000))
                .andExpect(jsonPath("$.balance.fixedLiving.amount").value(520000))
                .andExpect(jsonPath("$.balance.remittance.amount").value(700000))
                .andExpect(jsonPath("$.balance.variableExpense.amount").value(455000))
                .andExpect(jsonPath("$.balance.variableExpenseSource").value("TRANSACTIONS"))
                .andExpect(jsonPath("$.balance.spare.amount").value(675000))
                .andExpect(jsonPath("$.balance.isMock").value(true));
    }

    @Test
    void 꽉_찬_달이_없으면_201이고_금액은_null() throws Exception {
        long[] ids = succeededBatch(userId, "test-fin-3", "2026-10-05", "2026-10-31");
        txn(ids, "food", "2026-10-10", "EXPENSE", 12000, "FOOD", "NORMAL", "KRW");
        when(aiFinanceClient.analyze(any())).thenReturn(new AnalyzeResponse(null, null, null, null, null,
                new DataQuality(0, List.of(), List.of("2026-10"), List.of("NO_FULL_MONTH")), List.of()));

        analyze(null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.avgMonthlyIncome").isEmpty())
                .andExpect(jsonPath("$.fixedLiving").isEmpty())
                .andExpect(jsonPath("$.variableExpense").isEmpty())
                .andExpect(jsonPath("$.spare").isEmpty())
                .andExpect(jsonPath("$.dataQuality.observedMonthCount").value(0))
                .andExpect(jsonPath("$.dataQuality.partialMonths[0]").value("2026-10"))
                .andExpect(jsonPath("$.dataQuality.warnings[0]").value("NO_FULL_MONTH"));

        // 자른 기간이 비므로 원래 수집 기간을 그대로 보낸다
        AnalyzeRequest sent = sentRequest();
        assertThat(sent.periodFrom()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(sent.periodTo()).isEqualTo(LocalDate.of(2026, 10, 31));
        summary().andExpect(status().isOk()).andExpect(jsonPath("$.balance").isEmpty());
    }

    @Test
    void 적재_배치가_없으면_422이고_저장하지_않는다() throws Exception {
        analyze(null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.details.reason").value("NO_SUCCEEDED_BATCH"));

        assertThat(analysisCount(userId)).isZero();
        verify(aiFinanceClient, never()).analyze(any());
    }

    @Test
    void 기간_안_거래가_0건이면_422이고_저장하지_않는다() throws Exception {
        long[] ids = succeededBatch(userId, "test-fin-4", "2026-09-01", "2026-10-31");
        txn(ids, "usd", "2026-09-15", "EXPENSE", 50, "FOOD", "NORMAL", "USD");

        analyze(null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.details.reason").value("NO_TRANSACTIONS"));

        assertThat(analysisCount(userId)).isZero();
        verify(aiFinanceClient, never()).analyze(any());
    }

    @Test
    void AI_호출이_실패하면_502이고_FAILED를_저장하며_최신_결과는_이전_COMPLETED() throws Exception {
        long[] ids = succeededBatch(userId, "test-fin-5", "2026-09-01", "2026-10-31");
        txn(ids, "salary", "2026-09-05", "INCOME", 2350000, "SALARY", "NORMAL", "KRW");
        when(aiFinanceClient.analyze(any()))
                .thenReturn(result(List.of(), List.of()))
                .thenThrow(new AiCallException("AI_HTTP_503", null));

        Long completedId = idOf(analyze(null).andExpect(status().isCreated()));
        analyze(null)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"));

        Map<String, Object> failed = jdbcTemplate.queryForMap("SELECT status, error_code, avg_monthly_income "
                + "FROM financial_analyses WHERE user_id = ? ORDER BY id DESC LIMIT 1", userId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("error_code")).isEqualTo("AI_HTTP_503");
        assertThat(failed.get("avg_monthly_income")).isNull();
        latest().andExpect(status().isOk()).andExpect(jsonPath("$.id").value(completedId));
        summary().andExpect(jsonPath("$.balance.spare.amount").value(675000));
    }

    @Test
    void 같은_Idempotency_Key를_두_번_보내면_같은_결과이고_AI는_한_번만_호출() throws Exception {
        long[] ids = succeededBatch(userId, "test-fin-6", "2026-09-01", "2026-10-31");
        txn(ids, "salary", "2026-09-05", "INCOME", 2350000, "SALARY", "NORMAL", "KRW");
        when(aiFinanceClient.analyze(any())).thenReturn(result(List.of(), List.of()));

        Long first = idOf(analyze("fin-key-1").andExpect(status().isCreated()));
        analyze("fin-key-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.spare.amount").value(675000));

        verify(aiFinanceClient, times(1)).analyze(any());
        assertThat(analysisCount(userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT request_key FROM financial_analyses WHERE id = ?",
                String.class, first)).isEqualTo("fin-key-1");
    }

    @Test
    void 같은_Idempotency_Key의_기존_행이_FAILED면_409이고_AI를_다시_부르지_않는다() throws Exception {
        long[] ids = succeededBatch(userId, "test-fin-7", "2026-09-01", "2026-10-31");
        txn(ids, "salary", "2026-09-05", "INCOME", 2350000, "SALARY", "NORMAL", "KRW");
        when(aiFinanceClient.analyze(any())).thenThrow(new AiCallException("AI_UNREACHABLE", null));

        analyze("fin-key-failed").andExpect(status().isBadGateway());
        analyze("fin-key-failed")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        verify(aiFinanceClient, times(1)).analyze(any());
        assertThat(analysisCount(userId)).isEqualTo(1);
    }

    @Test
    void isMock은_조회_시점의_최신_배치가_아니라_분석에_쓴_배치_기준() throws Exception {
        long[] mockBatch = succeededBatch(userId, "test-fin-8", "2026-09-01", "2026-10-31");
        txn(mockBatch, "salary", "2026-09-05", "INCOME", 2350000, "SALARY", "NORMAL", "KRW");
        when(aiFinanceClient.analyze(any())).thenReturn(result(List.of(), List.of()));
        Long id = idOf(analyze("fin-key-mock").andExpect(status().isCreated())
                .andExpect(jsonPath("$.isMock").value(true)));

        // 분석 뒤에 실연동(비 Mock) 배치가 새로 SUCCEEDED 가 되어도 기존 결과의 isMock 은 그대로
        jdbcTemplate.update("INSERT INTO ingestion_batches (user_id, source_type, is_mock, request_key, status, "
                + "coverage_from, coverage_to, started_at, finished_at) VALUES (?, 'MYDATA', FALSE, 'test-fin-8-real', "
                + "'SUCCEEDED', '2026-09-01', '2026-10-31', NOW(3), NOW(3))", userId);

        latest().andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.isMock").value(true));
        analyze("fin-key-mock").andExpect(status().isOk()).andExpect(jsonPath("$.isMock").value(true));
        summary().andExpect(jsonPath("$.balance.isMock").value(true));

        // 새로 계산하면 이번에 쓴(비 Mock) 배치 기준
        analyze(null).andExpect(status().isCreated()).andExpect(jsonPath("$.isMock").value(false));
        latest().andExpect(jsonPath("$.isMock").value(false));
        summary().andExpect(jsonPath("$.balance.isMock").value(false));
    }

    @Test
    void 최신_결과가_없으면_404이고_다른_사용자_결과는_보이지_않는다() throws Exception {
        Long otherId = createUser("test-fin-other", "TEST FIN OTHER");
        jdbcTemplate.update("INSERT INTO financial_analyses (user_id, request_key, period_from, period_to, status, "
                + "engine_version, avg_monthly_income, created_at) VALUES (?, 'other-key', '2026-09-01', '2026-10-31', "
                + "'COMPLETED', 'test', 9999999, NOW(3))", otherId);

        latest()
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void Idempotency_Key가_100자를_넘으면_400() throws Exception {
        analyze("k".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private ResultActions analyze(String idempotencyKey) throws Exception {
        var request = post(ANALYSES).header(HttpHeaders.AUTHORIZATION, bearer);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private ResultActions latest() throws Exception {
        return mockMvc.perform(get(ANALYSES + "/latest").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions summary() throws Exception {
        return mockMvc.perform(get("/api/v1/onboarding/summary").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private static Long idOf(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        return Long.valueOf(body.replaceAll("(?s)^\\{\"id\":(\\d+).*$", "$1"));
    }

    private AnalyzeRequest sentRequest() {
        ArgumentCaptor<AnalyzeRequest> captor = ArgumentCaptor.forClass(AnalyzeRequest.class);
        verify(aiFinanceClient).analyze(captor.capture());
        return captor.getValue();
    }

    /** 소득 2,350,000 − 고정 520,000 − 송금 700,000 − 변동 455,000 = 675,000. 꽉 찬 달 2개. */
    private static AnalyzeResponse result(List<String> warnings, List<Excluded> excluded) {
        return new AnalyzeResponse(Money.krw(2350000), Money.krw(520000), Money.krw(700000),
                new VariableExpense(Money.krw(455000), Money.krw(400000), Money.krw(510000)), Money.krw(675000),
                new DataQuality(2, List.of(), List.of(), warnings), excluded);
    }

    private Long createUser(String key, String name) {
        jdbcTemplate.update("INSERT INTO users (external_user_key, display_name) VALUES (?, ?)", key, name);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class, key);
    }

    private long analysisCount(Long ownerId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM financial_analyses WHERE user_id = ?", Long.class,
                ownerId);
    }

    /** SUCCEEDED Mock 배치와 계좌. [batchId, accountId] */
    private long[] succeededBatch(Long ownerId, String key, String coverageFrom, String coverageTo) {
        jdbcTemplate.update("INSERT INTO ingestion_batches (user_id, source_type, request_key, status, coverage_from, "
                + "coverage_to, started_at, finished_at) VALUES (?, 'MOCK', ?, 'SUCCEEDED', ?, ?, NOW(3), NOW(3))",
                ownerId, key, coverageFrom, coverageTo);
        Long batchId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("INSERT INTO financial_accounts (user_id, institution_id, batch_id, external_account_key, "
                + "account_no_masked, account_type) VALUES (?, (SELECT id FROM institutions "
                + "WHERE institution_code = 'SHINHAN_BANK'), ?, ?, '110-***-**0000', 'DEMAND')", ownerId, batchId, key);
        Long accountId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new long[] {batchId, accountId};
    }

    private long txn(long[] batchAndAccount, String key, String date, String direction, long amount, String category,
                     String kind, String currency) {
        jdbcTemplate.update("INSERT INTO financial_transactions (user_id, account_id, batch_id, external_txn_key, "
                + "transacted_at, booked_date, direction, amount, currency, category_code, transaction_kind) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, batchAndAccount[1], batchAndAccount[0], key, date + " 01:00:00", date, direction, amount,
                currency, category, kind);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
