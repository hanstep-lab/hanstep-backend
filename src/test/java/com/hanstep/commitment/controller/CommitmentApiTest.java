package com.hanstep.commitment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.mydata.domain.MydataScope;
import com.hanstep.mydata.service.MockIngestionService;
import com.hanstep.mydata.service.MockMydataReader;
import com.hanstep.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

/** CMT-01 ~ 03. 실제 DB(데모 사용자 demo-e9-nguyen), 테스트마다 롤백. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CommitmentApiTest {

    private static final String RENT = item("RENT", "월세", 400000);
    private static final String UTILITY = item("UTILITY", "공과금", 70000);
    private static final String ELECTRICITY_WATER = item("UTILITY", "전기·수도", 50000);
    private static final String REMITTANCE = item("REMITTANCE", "가족 송금", 700000);
    private static final String VARIABLE_EXPENSE = item("VARIABLE_EXPENSE", "식비·교통·여가", 450000);
    private static final String MANDATORY_SAVING = item("MANDATORY_SAVING", "적금", 100000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockIngestionService ingestionService;

    @Autowired
    private MockMydataReader reader;

    @Autowired
    private EntityManager entityManager;

    private Long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        userId = userRepository.findByExternalUserKey("demo-e9-nguyen").orElseThrow().getId();
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        jdbcTemplate.update("DELETE FROM monthly_commitments WHERE user_id = ?", userId); // 롤백되므로 실제 데이터는 그대로
    }

    @Test
    void 저장한_항목이_없으면_빈_목록과_0_합계() throws Exception {
        mockMvc.perform(get("/api/v1/commitments").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totals.fixedLiving.amount").value(0))
                .andExpect(jsonPath("$.totals.deductibleTotal.amount").value(0))
                .andExpect(jsonPath("$.totals.deductibleTotal.currency").value("KRW"));
    }

    @Test
    void 저장하면_목록과_유형별_합계를_돌려준다() throws Exception {
        save(String.join(",", RENT, UTILITY, ELECTRICITY_WATER, REMITTANCE, VARIABLE_EXPENSE, MANDATORY_SAVING))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(6));

        mockMvc.perform(get("/api/v1/commitments").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(6))
                .andExpect(jsonPath("$.items[0].commitmentType").value("RENT"))
                .andExpect(jsonPath("$.items[0].label").value("월세"))
                .andExpect(jsonPath("$.items[0].amount.amount").value(400000))
                .andExpect(jsonPath("$.items[0].source").value("USER"))
                .andExpect(jsonPath("$.items[0].dueDay").doesNotExist())
                .andExpect(jsonPath("$.totals.fixedLiving.amount").value(520000))
                .andExpect(jsonPath("$.totals.remittance.amount").value(700000))
                .andExpect(jsonPath("$.totals.variableExpense.amount").value(450000))
                .andExpect(jsonPath("$.totals.mandatorySaving.amount").value(100000))
                .andExpect(jsonPath("$.totals.deductibleTotal.amount").value(1670000)); // 저축은 빼지 않음
    }

    @Test
    void 전체_교체하면_빠진_항목은_비활성으로_남고_같은_항목은_그대로() throws Exception {
        save(String.join(",", RENT, UTILITY, ELECTRICITY_WATER, REMITTANCE, VARIABLE_EXPENSE))
                .andExpect(status().isOk());
        Long rentId = jdbcTemplate.queryForObject(
                "SELECT id FROM monthly_commitments WHERE user_id = ? AND commitment_type = 'RENT'", Long.class, userId);

        save(String.join(",", RENT, UTILITY, item("VARIABLE_EXPENSE", "식비·교통·여가", 300000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(rentId))
                .andExpect(jsonPath("$.totals.fixedLiving.amount").value(470000))
                .andExpect(jsonPath("$.totals.remittance.amount").value(0))
                .andExpect(jsonPath("$.totals.deductibleTotal.amount").value(770000));

        assertThat(jdbcTemplate.queryForList(
                "SELECT CONCAT(commitment_type, ':', CAST(amount AS UNSIGNED), ':', is_active) "
                        + "FROM monthly_commitments WHERE user_id = ? ORDER BY id",
                String.class, userId))
                .containsExactly("RENT:400000:1", "UTILITY:70000:1", "UTILITY:50000:0", "REMITTANCE:700000:0",
                        "VARIABLE_EXPENSE:450000:0", "VARIABLE_EXPENSE:300000:1");
    }

    @Test
    void 같은_요청을_두_번_저장하면_id가_그대로이고_행이_늘지_않는다() throws Exception {
        String items = String.join(",", RENT, UTILITY, REMITTANCE, VARIABLE_EXPENSE);
        save(items).andExpect(status().isOk());
        List<Long> firstIds = activeIds();

        save(items).andExpect(status().isOk());

        assertThat(activeIds()).isEqualTo(firstIds).hasSize(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monthly_commitments WHERE user_id = ?", Integer.class, userId)).isEqualTo(4);
    }

    @Test
    void 같은_항목_2건은_다시_저장해도_활성_2행이_유지된다() throws Exception {
        String twoUtilities = String.join(",", UTILITY, UTILITY);
        save(twoUtilities).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        List<Long> firstIds = activeIds();

        save(twoUtilities)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totals.fixedLiving.amount").value(140000));

        assertThat(activeIds()).isEqualTo(firstIds).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monthly_commitments WHERE user_id = ?", Integer.class, userId)).isEqualTo(2);
    }

    @Test
    void 송금_이력에서_고른_본인_거래는_저장된다() throws Exception {
        long txnId = insertTransaction(userId, "test-own");

        save(detected(txnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].commitmentType").value("REMITTANCE"))
                .andExpect(jsonPath("$.items[0].label").value("Vietcombank"))
                .andExpect(jsonPath("$.items[0].source").value("DETECTED_CONFIRMED"))
                .andExpect(jsonPath("$.items[0].detectedFromTxnId").value(txnId));
    }

    @Test
    void 없는_거래나_남의_거래를_근거로_보내면_404() throws Exception {
        save(detected(999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        jdbcTemplate.update("INSERT INTO users (external_user_key, display_name) VALUES ('test-other', 'OTHER USER')");
        Long otherUserId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long othersTxnId = insertTransaction(otherUserId, "test-other");
        save(detected(othersTxnId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monthly_commitments WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    void 유형이_없으면_400() throws Exception {
        save("{\"label\":\"월세\",\"amount\":{\"amount\":400000,\"currency\":\"KRW\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void KRW가_아닌_통화는_400() throws Exception {
        save(RENT + ",{\"commitmentType\":\"REMITTANCE\",\"amount\":{\"amount\":500,\"currency\":\"USD\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("items[1].amount.currency"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monthly_commitments WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    void DETECTED_CONFIRMED인데_근거_거래가_없으면_400() throws Exception {
        save(RENT + ",{\"commitmentType\":\"REMITTANCE\",\"amount\":{\"amount\":500000,\"currency\":\"KRW\"},"
                + "\"source\":\"DETECTED_CONFIRMED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("items[1].detectedFromTxnId"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monthly_commitments WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    void 근거_거래가_있는데_source가_없으면_USER로_보고_400() throws Exception {
        // 없는 거래 ID 라 짝 검사가 거래 확인보다 먼저면 404 가 아니라 400 이 나온다
        save("{\"commitmentType\":\"REMITTANCE\",\"amount\":{\"amount\":500000,\"currency\":\"KRW\"},"
                + "\"detectedFromTxnId\":999999999}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("items[0].detectedFromTxnId"));
    }

    @Test
    void 토큰_없이_호출하면_401() throws Exception {
        mockMvc.perform(get("/api/v1/commitments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 송금_이력은_Mock_적재_후_월평균_큰_순으로_돌려준다() throws Exception {
        ingestDemo();
        Long maiId = jdbcTemplate.queryForObject("SELECT id FROM remittance_recipients WHERE user_id = ? "
                + "AND external_recipient_key = 'mock-recipient-mother-vcb'", Long.class, userId);

        candidates()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzedMonths").value(12))
                .andExpect(jsonPath("$.isMock").value(true))
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].rank").value(1))
                .andExpect(jsonPath("$.candidates[0].counterpartyName").value("NGUYEN THI MAI"))
                .andExpect(jsonPath("$.candidates[0].counterpartyBank").value("Vietcombank"))
                .andExpect(jsonPath("$.candidates[0].avgMonthlyAmount.amount").value(500000))
                .andExpect(jsonPath("$.candidates[0].monthsSent").value(12))
                .andExpect(jsonPath("$.candidates[0].lastSentOn").value("2026-10-10"))
                .andExpect(jsonPath("$.candidates[0].lastTransactionId").value(txnId("SHB-20261010-RMT-MOTHER")))
                .andExpect(jsonPath("$.candidates[0].recipientId").value(maiId))
                .andExpect(jsonPath("$.candidates[0].sameNameAsUser").value(false))
                .andExpect(jsonPath("$.candidates[1].rank").value(2))
                .andExpect(jsonPath("$.candidates[1].counterpartyName").value("NGUYEN VAN MINH"))
                .andExpect(jsonPath("$.candidates[1].counterpartyBank").value("Techcombank"))
                .andExpect(jsonPath("$.candidates[1].avgMonthlyAmount.amount").value(200000))
                .andExpect(jsonPath("$.candidates[1].recipientId").isEmpty())
                .andExpect(jsonPath("$.candidates[1].sameNameAsUser").value(true));
    }

    @Test
    void 송금_이력은_적재_배치가_없으면_빈_목록() throws Exception {
        candidates()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(0))
                .andExpect(jsonPath("$.analyzedMonths").value(0))
                .andExpect(jsonPath("$.isMock").value(false));
    }

    @Test
    void 송금_이력은_한_달만_보낸_송금처를_뺀다() throws Exception {
        ingestDemo();
        long[] ids = succeededBatch(userId, "test-one-month");
        remit(userId, ids, "test-one-month-1", "TRAN VAN BINH", "BIDV", "2026-09-03", 900000);
        remit(userId, ids, "test-one-month-2", "TRAN VAN BINH", "BIDV", "2026-09-20", 900000);

        candidates()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].counterpartyName").value("NGUYEN THI MAI"))
                .andExpect(jsonPath("$.candidates[1].counterpartyName").value("NGUYEN VAN MINH"));
    }

    @Test
    void 송금_이력의_마지막_거래는_같은_날짜면_id가_큰_쪽() throws Exception {
        long[] ids = succeededBatch(userId, "test-same-day");
        remit(userId, ids, "test-same-day-1", "TRAN VAN BINH", "BIDV", "2026-09-10", 300000);
        remit(userId, ids, "test-same-day-2", "TRAN VAN BINH", "BIDV", "2026-10-10", 300000);
        long last = remit(userId, ids, "test-same-day-3", "TRAN VAN BINH", "BIDV", "2026-10-10", 300000);

        candidates()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].monthsSent").value(2))
                .andExpect(jsonPath("$.candidates[0].avgMonthlyAmount.amount").value(75000)) // 900,000 ÷ 12
                .andExpect(jsonPath("$.candidates[0].lastSentOn").value("2026-10-10"))
                .andExpect(jsonPath("$.candidates[0].lastTransactionId").value(last));
    }

    @Test
    void 송금_이력은_수집_시작일이_월초가_아니면_그_달을_빼고_센다() throws Exception {
        long[] ids = succeededBatch(userId, "test-mid-start", "2025-11-15", "2026-10-31");
        remitMonthly(ids, "test-mid-start", LocalDate.of(2025, 11, 20), 12, 300000);

        candidates()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzedMonths").value(11))
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].monthsSent").value(11)) // 2025-11-20 은 빠진다
                .andExpect(jsonPath("$.candidates[0].avgMonthlyAmount.amount").value(300000))
                .andExpect(jsonPath("$.candidates[0].lastSentOn").value("2026-10-20"));
    }

    @Test
    void 송금_이력은_수집_종료일이_월말이_아니면_그_달을_빼고_센다() throws Exception {
        long[] ids = succeededBatch(userId, "test-mid-end", "2025-11-01", "2026-10-15");
        remitMonthly(ids, "test-mid-end", LocalDate.of(2025, 11, 10), 12, 300000);

        candidates()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzedMonths").value(11))
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].monthsSent").value(11)) // 2026-10-10 은 빠진다
                .andExpect(jsonPath("$.candidates[0].avgMonthlyAmount.amount").value(300000))
                .andExpect(jsonPath("$.candidates[0].lastSentOn").value("2026-09-10"));
    }

    @Test
    void 송금_이력은_온전한_달이_없으면_빈_목록() throws Exception {
        long[] ids = succeededBatch(userId, "test-no-full-month", "2026-10-05", "2026-10-31");
        remit(userId, ids, "test-no-full-month-1", "TRAN VAN BINH", "BIDV", "2026-10-10", 300000);

        candidates()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzedMonths").value(0))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test
    void 송금_이력에_다른_사용자의_거래는_섞이지_않는다() throws Exception {
        jdbcTemplate.update("INSERT INTO users (external_user_key, display_name) VALUES ('test-other', 'OTHER USER')");
        Long otherUserId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long[] ids = succeededBatch(otherUserId, "test-other");
        remit(otherUserId, ids, "test-other-1", "NGUYEN THI MAI", "Vietcombank", "2026-08-10", 9000000);
        remit(otherUserId, ids, "test-other-2", "NGUYEN THI MAI", "Vietcombank", "2026-09-10", 9000000);
        remit(otherUserId, ids, "test-other-3", "LE THI HOA", "BIDV", "2026-09-10", 9000000);
        remit(otherUserId, ids, "test-other-4", "LE THI HOA", "BIDV", "2026-10-10", 9000000);
        ingestDemo();

        candidates()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].counterpartyName").value("NGUYEN THI MAI"))
                .andExpect(jsonPath("$.candidates[0].avgMonthlyAmount.amount").value(500000))
                .andExpect(jsonPath("$.candidates[1].counterpartyName").value("NGUYEN VAN MINH"));
    }

    @Test
    void 송금_이력은_토큰_없으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/commitments/remittance-candidates"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private ResultActions save(String itemsJson) throws Exception {
        return mockMvc.perform(put("/api/v1/commitments").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[" + itemsJson + "]}"));
    }

    private List<Long> activeIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM monthly_commitments WHERE user_id = ? AND is_active ORDER BY id", Long.class, userId);
    }

    private static String item(String type, String label, long amount) {
        return "{\"commitmentType\":\"" + type + "\",\"label\":\"" + label + "\",\"amount\":{\"amount\":" + amount
                + ",\"currency\":\"KRW\"}}";
    }

    /** CMT-03 에서 고른 송금. label 에는 FE 가 받는 은행명을 넣는다. */
    private static String detected(long txnId) {
        return "{\"commitmentType\":\"REMITTANCE\",\"label\":\"Vietcombank\",\"amount\":{\"amount\":500000,"
                + "\"currency\":\"KRW\"},\"source\":\"DETECTED_CONFIRMED\",\"detectedFromTxnId\":" + txnId + "}";
    }

    private ResultActions candidates() throws Exception {
        return mockMvc.perform(get("/api/v1/commitments/remittance-candidates")
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** 데모 사용자 신한은행 Mock 원천 12개월 적재(MYD-02 적재 부분). 테스트 트랜잭션에 묶여 롤백된다. */
    private void ingestDemo() {
        jdbcTemplate.update("INSERT INTO user_consents (user_id, consent_type, policy_version, status, granted_at) "
                + "VALUES (?, 'MYDATA', 'test', 'GRANTED', NOW(3))", userId);
        Long consentId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Long bankId = jdbcTemplate.queryForObject(
                "SELECT id FROM institutions WHERE institution_code = 'SHINHAN_BANK'", Long.class);
        ingestionService.ingest(userId, "test-cmt03", consentId,
                List.of(MydataScope.ACCOUNT_BALANCE, MydataScope.TRANSACTIONS), 12,
                List.of(new MockIngestionService.Target(bankId, reader.read("SHINHAN_BANK").orElseThrow())));
        // 테스트에서는 적재가 바깥 트랜잭션에 합류해 커밋되지 않으므로, 배치 SUCCEEDED 갱신을 JdbcTemplate 조회 전에 반영
        entityManager.flush();
    }

    private Long txnId(String externalTxnKey) {
        return jdbcTemplate.queryForObject("SELECT id FROM financial_transactions WHERE user_id = ? "
                + "AND external_txn_key = ?", Long.class, userId, externalTxnKey);
    }

    /** 수집 기간 2025-11-01 ~ 2026-10-31 인 SUCCEEDED 배치와 계좌. [batchId, accountId] */
    private long[] succeededBatch(Long ownerId, String key) {
        return succeededBatch(ownerId, key, "2025-11-01", "2026-10-31");
    }

    /** 수집 기간을 지정한 SUCCEEDED 배치와 계좌. [batchId, accountId] */
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

    private long remit(Long ownerId, long[] batchAndAccount, String key, String name, String bank, String date,
                       long amount) {
        jdbcTemplate.update("INSERT INTO financial_transactions (user_id, account_id, batch_id, external_txn_key, "
                + "transacted_at, booked_date, direction, amount, counterparty_name, counterparty_bank, "
                + "category_code) VALUES (?, ?, ?, ?, ?, ?, 'EXPENSE', ?, ?, ?, 'REMITTANCE')",
                ownerId, batchAndAccount[1], batchAndAccount[0], key, date + " 01:00:00", date, amount, name, bank);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** start 부터 매달 같은 날 count 번, 같은 받는 분(TRAN VAN BINH, BIDV)에게 송금. */
    private void remitMonthly(long[] batchAndAccount, String key, LocalDate start, int count, long amount) {
        for (int i = 0; i < count; i++) {
            remit(userId, batchAndAccount, key + "-" + i, "TRAN VAN BINH", "BIDV", start.plusMonths(i).toString(),
                    amount);
        }
    }

    /** 배치·계좌·거래 한 건을 직접 넣는다(PENDING 배치라 CMT-03 집계 대상 아님). */
    private long insertTransaction(Long ownerId, String key) {
        jdbcTemplate.update("INSERT INTO ingestion_batches (user_id, source_type, request_key, started_at) "
                + "VALUES (?, 'MOCK', ?, NOW(3))", ownerId, key);
        Long batchId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("INSERT INTO financial_accounts (user_id, institution_id, batch_id, external_account_key, "
                + "account_no_masked, account_type) VALUES (?, (SELECT id FROM institutions "
                + "WHERE institution_code = 'SHINHAN_BANK'), ?, ?, '110-***-**0000', 'DEMAND')", ownerId, batchId, key);
        Long accountId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("INSERT INTO financial_transactions (user_id, account_id, batch_id, external_txn_key, "
                + "transacted_at, booked_date, direction, amount, counterparty_bank, category_code) "
                + "VALUES (?, ?, ?, ?, '2026-10-10 01:00:00', '2026-10-10', 'EXPENSE', 500000, 'Vietcombank', "
                + "'REMITTANCE')", ownerId, accountId, batchId, key);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
