package com.hanstep.commitment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.user.repository.UserRepository;
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

/** CMT-01 ~ 02. 실제 DB(데모 사용자 demo-e9-nguyen), 테스트마다 롤백. */
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

    /** MYD-02 전이라 거래 테이블이 비어 있어 배치·계좌·거래 한 건을 직접 넣는다. */
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
