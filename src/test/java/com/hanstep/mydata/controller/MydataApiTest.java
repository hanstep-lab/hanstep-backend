package com.hanstep.mydata.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * MYD-02. PENDING 배치가 실제로 커밋돼야 멱등·동시성을 볼 수 있어 롤백하지 않는다.
 * 테스트 전용 사용자를 만들고 끝나면 지운다(users ON DELETE CASCADE 로 동의·연결·배치·계좌·거래도 지워짐).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MydataApiTest {

    private static final String TEST_USER_KEY = "test-mydata-api";
    private static final int BANK_TXNS = 96;
    private static final int CARD_TXNS = 245;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private String bearer;
    private String body;

    @BeforeEach
    void setUp() {
        deleteTestUser();
        jdbcTemplate.update("INSERT INTO users (external_user_key, display_name) VALUES (?, 'TEST MYDATA')",
                TEST_USER_KEY);
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class,
                TEST_USER_KEY);
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        grant("MYDATA");
        grant("MYDATA_TERMS");
        body = "{\"institutionIds\":[" + institutionId("SHINHAN_BANK") + "," + institutionId("SHINHAN_CARD")
                + "],\"scopes\":[\"ACCOUNT_BALANCE\",\"TRANSACTIONS\",\"CARD_USAGE\"],\"lookbackMonths\":12}";
    }

    @AfterEach
    void tearDown() {
        deleteTestUser();
    }

    @Test
    void 은행_96건과_카드_245건을_적재하고_연결_상태를_돌려준다() throws Exception {
        connect("key-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.connections.length()").value(2))
                .andExpect(jsonPath("$.connections[0].institutionCode").value("SHINHAN_BANK"))
                .andExpect(jsonPath("$.connections[0].status").value("CONNECTED"))
                .andExpect(jsonPath("$.connections[0].lookbackMonths").value(12))
                .andExpect(jsonPath("$.connections[0].accountCount").value(1))
                .andExpect(jsonPath("$.connections[0].transactionCount").value(BANK_TXNS))
                .andExpect(jsonPath("$.connections[0].isMock").value(true))
                .andExpect(jsonPath("$.connections[1].institutionCode").value("SHINHAN_CARD"))
                .andExpect(jsonPath("$.connections[1].accountCount").value(1))
                .andExpect(jsonPath("$.connections[1].transactionCount").value(CARD_TXNS));

        Map<String, Object> batch = jdbcTemplate.queryForMap("SELECT source_type, is_mock, status, record_count, "
                + "coverage_from, coverage_to, finished_at FROM ingestion_batches WHERE user_id = ?", userId);
        assertThat(batch.get("source_type")).isEqualTo("MOCK");
        assertThat(batch.get("is_mock")).isEqualTo(true);
        assertThat(batch.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Number) batch.get("record_count")).intValue()).isEqualTo(BANK_TXNS + CARD_TXNS);
        assertThat(batch.get("coverage_from").toString()).isEqualTo("2025-11-01");
        assertThat(batch.get("coverage_to").toString()).isEqualTo("2026-10-31");
        assertThat(batch.get("finished_at")).isNotNull();
        assertThat(transactionCount()).isEqualTo(BANK_TXNS + CARD_TXNS);

        // _dtime 은 KST → transacted_at 은 UTC, booked_date 는 KST 날짜(자정 근처면 날짜가 달라진다)
        assertThat(jdbcTemplate.queryForObject("SELECT CONCAT(transacted_at, ' / ', booked_date, ' / ', direction) "
                + "FROM financial_transactions WHERE user_id = ? AND external_txn_key = 'SHC-20251101-01'",
                String.class, userId)).isEqualTo("2025-10-31 22:15:12.000 / 2025-11-01 / EXPENSE");
        assertThat(jdbcTemplate.queryForObject("SELECT CONCAT(transacted_at, ' / ', booked_date, ' / ', direction) "
                + "FROM financial_transactions WHERE user_id = ? AND external_txn_key = 'SHB-20251105-SAL'",
                String.class, userId)).isEqualTo("2025-11-05 00:00:00.000 / 2025-11-05 / INCOME");
    }

    @Test
    void 같은_키로_다시_요청하면_200이고_행_수는_그대로() throws Exception {
        connect("key-1").andExpect(status().isCreated());

        connect("key-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections.length()").value(2))
                .andExpect(jsonPath("$.connections[0].transactionCount").value(BANK_TXNS))
                .andExpect(jsonPath("$.connections[1].transactionCount").value(CARD_TXNS));

        assertThat(batchCount()).isEqualTo(1);
        assertThat(transactionCount()).isEqualTo(BANK_TXNS + CARD_TXNS);
    }

    @Test
    void 다른_키로_다시_연결해도_이미_있는_거래는_넣지_않는다() throws Exception {
        connect("key-1").andExpect(status().isCreated());

        connect("key-2").andExpect(status().isCreated());

        assertThat(batchCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT record_count FROM ingestion_batches WHERE user_id = ? "
                + "AND request_key = 'key-2'", Integer.class, userId)).isZero();
        assertThat(transactionCount()).isEqualTo(BANK_TXNS + CARD_TXNS);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM financial_accounts WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM data_connections WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(2);
    }

    @Test
    void 같은_키로_10개를_동시에_보내도_배치_1개_거래_341행() throws Exception {
        int requests = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return connect("same-key").andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).allMatch(s -> s == 201 || s == 200 || s == 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(batchCount()).isEqualTo(1);
        assertThat(transactionCount()).isEqualTo(BANK_TXNS + CARD_TXNS);
    }

    @Test
    void 같은_키가_처리_중이면_409_DUPLICATE_REQUEST() throws Exception {
        insertBatch("in-progress", "PENDING");

        connect("in-progress")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(transactionCount()).isZero();
    }

    @Test
    void 같은_키가_실패했으면_409_INVALID_STATE() throws Exception {
        insertBatch("failed", "FAILED");

        connect("failed")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(transactionCount()).isZero();
    }

    @Test
    void Idempotency_Key가_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/mydata/connections").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("Idempotency-Key"));
        assertThat(batchCount()).isZero();
    }

    @Test
    void 토큰_없이_호출하면_401() throws Exception {
        mockMvc.perform(post("/api/v1/mydata/connections").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 마이데이터_동의가_없으면_403() throws Exception {
        jdbcTemplate.update("DELETE FROM user_consents WHERE user_id = ? AND consent_type = 'MYDATA_TERMS'", userId);

        connect("key-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.details.consentType").value("MYDATA_TERMS"));
        assertThat(batchCount()).isZero();
    }

    @Test
    void 없는_기관_ID면_404() throws Exception {
        mockMvc.perform(post("/api/v1/mydata/connections").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutionIds\":[999999],\"scopes\":[\"TRANSACTIONS\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(batchCount()).isZero();
    }

    private ResultActions connect(String key) throws Exception {
        return mockMvc.perform(post("/api/v1/mydata/connections").header(HttpHeaders.AUTHORIZATION, bearer)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void grant(String type) {
        jdbcTemplate.update("INSERT INTO user_consents (user_id, consent_type, policy_version, status, granted_at) "
                + "VALUES (?, ?, 'test', 'GRANTED', NOW(3))", userId, type);
    }

    private void insertBatch(String key, String batchStatus) {
        jdbcTemplate.update("INSERT INTO ingestion_batches (user_id, source_type, request_key, status, started_at) "
                + "VALUES (?, 'MOCK', ?, ?, NOW(3))", userId, key, batchStatus);
    }

    private Long institutionId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM institutions WHERE institution_code = ?", Long.class,
                code);
    }

    private int batchCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_batches WHERE user_id = ?",
                Integer.class, userId);
    }

    private int transactionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM financial_transactions WHERE user_id = ?",
                Integer.class, userId);
    }

    private void deleteTestUser() {
        jdbcTemplate.update("DELETE FROM users WHERE external_user_key = ?", TEST_USER_KEY);
    }
}
