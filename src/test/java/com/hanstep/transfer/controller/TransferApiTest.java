package com.hanstep.transfer.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.security.JwtTokenProvider;
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
 * TRF-01·02(화면)와 INT-14·17·18(챗봇 내부). 실제 DB, 테스트마다 롤백.
 * 데모 사용자 대신 테스트 사용자를 만들고(로컬 DB 의 demo-setup 데이터와 무관), 환율도 테스트 안에서 넣는다.
 * PHP 22.3200(MOCK)을 먼 미래 기준일(2099-01-02)로 넣어, 로컬 DB 에 04 시드나 수집한 실데이터가 있어도 이 행이 최신값이 된다.
 */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class TransferApiTest {

    private static final String INTERNAL = "/internal/v1/ai-context";
    private static final String RATE_DATE = "2099-01-02";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private long userId;
    private String bearer;
    private long motherId;
    private long selfId;

    @BeforeEach
    void setUp() {
        userId = insertUser("test-transfer", "MARK ANTHONY REYES");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        motherId = insertRecipient(userId, "test-mother", "LORNA REYES", "MOTHER", "BDO", "****5566", true);
        selfId = insertRecipient(userId, "test-self", "MARK ANTHONY REYES", "SELF", "BPI", "****7788", true);
        insertRecipient(userId, "test-unconfirmed", "JUAN DELA CRUZ", "OTHER", "BPI", "****1234", false);
        insertAccount(userId, 1_000_000);
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                + "receive_rate, source, is_mock, fetched_at) VALUES ('PHP', 1, ?, '2099-01-01 15:00:00', 22.3200, "
                + "22.5432, 22.0968, 'MOCK', TRUE, '2099-01-01 15:00:00')", RATE_DATE);
    }

    // ---- TRF-01 ----

    @Test
    void 화면_조회는_어머니를_먼저_고르고_관계_확인한_받는_분만_준다() throws Exception {
        mockMvc.perform(get("/api/v1/transfers/context").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient.recipientId").value(motherId))
                .andExpect(jsonPath("$.recipient.name").value("LORNA REYES"))
                .andExpect(jsonPath("$.recipient.relation").value("어머니"))
                .andExpect(jsonPath("$.recipient.bank").value("BDO"))
                .andExpect(jsonPath("$.recipient.accountLast4").value("5566"))
                .andExpect(jsonPath("$.recipient.country").value("필리핀"))
                .andExpect(jsonPath("$.recipient.countryCode").value("PH"))
                .andExpect(jsonPath("$.recipient.currency").value("PHP"))
                .andExpect(jsonPath("$.recipients.length()").value(2))
                .andExpect(jsonPath("$.recipients[1].recipientId").value(selfId))
                .andExpect(jsonPath("$.account.name").value("신한 주거래 우대통장"))
                .andExpect(jsonPath("$.account.number").value("110-***-**4821"))
                .andExpect(jsonPath("$.account.balance.amount").value(1_000_000))
                .andExpect(jsonPath("$.account.balance.currency").value("KRW"))
                .andExpect(jsonPath("$.purpose.code").value("FAMILY_SUPPORT"))
                .andExpect(jsonPath("$.purpose.label").value("가족 생활비 (Family support)"))
                .andExpect(jsonPath("$.quickAmounts.length()").value(3))
                .andExpect(jsonPath("$.quickAmounts[1]").value(500_000))
                .andExpect(jsonPath("$.isMock").value(true));
    }

    @Test
    void 본인_계좌를_고르면_본인_계좌_송금_목적() throws Exception {
        mockMvc.perform(get("/api/v1/transfers/context").param("recipientId", String.valueOf(selfId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient.relation").value("본인 계좌"))
                .andExpect(jsonPath("$.recipient.bank").value("BPI"))
                .andExpect(jsonPath("$.purpose.code").value("OWN_ACCOUNT"))
                .andExpect(jsonPath("$.purpose.label").value("본인 계좌 송금 (Own account)"));
    }

    @Test
    void 화면_조회에서_남의_받는_분은_404() throws Exception {
        long otherRecipient = otherUsersRecipient();
        mockMvc.perform(get("/api/v1/transfers/context").param("recipientId", String.valueOf(otherRecipient))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_FOUND"));
    }

    @Test
    void 받는_분이_없으면_404_계좌가_없으면_422() throws Exception {
        long empty = insertUser("test-transfer-empty", "EMPTY");
        mockMvc.perform(get("/api/v1/transfers/context").header(HttpHeaders.AUTHORIZATION, bearerOf(empty)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        insertRecipient(empty, "test-empty-mother", "LORNA REYES", "MOTHER", "BDO", "****5566", true);
        mockMvc.perform(get("/api/v1/transfers/context").header(HttpHeaders.AUTHORIZATION, bearerOf(empty)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.details.reason").value("NO_ACCOUNT"));
    }

    @Test
    void 화면_API는_토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/transfers/context")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/transfers/quote").param("amount", "500000").param("currency", "KRW")
                        .param("recipientId", String.valueOf(motherId)))
                .andExpect(status().isUnauthorized());
    }

    // ---- TRF-02 ----

    @Test
    void 보낼_원화_50만원은_PHP_22401() throws Exception {
        quote(500_000, "KRW", motherId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientId").value(motherId))
                .andExpect(jsonPath("$.sendAmount.amount").value(500_000))
                .andExpect(jsonPath("$.sendAmount.currency").value("KRW"))
                .andExpect(jsonPath("$.receiveAmount.amount").value(22_401)) // 22,401.43 버림
                .andExpect(jsonPath("$.receiveAmount.currency").value("PHP"))
                .andExpect(jsonPath("$.appliedRate.currency").value("PHP"))
                .andExpect(jsonPath("$.appliedRate.unit").value(1))
                .andExpect(jsonPath("$.appliedRate.rate").value(22.32))
                .andExpect(jsonPath("$.appliedRate.sourceLabel").value("ECB 기준환율 기반 참고값"))
                .andExpect(jsonPath("$.appliedRate.asOf").value(RATE_DATE))
                .andExpect(jsonPath("$.appliedRate.isMock").value(true));
    }

    @Test
    void 받을_PHP_2만은_보낼_원화_446400() throws Exception {
        quote(20_000, "PHP", motherId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sendAmount.amount").value(446_400))
                .andExpect(jsonPath("$.receiveAmount.amount").value(20_000))
                .andExpect(jsonPath("$.receiveAmount.currency").value("PHP"));
    }

    @Test
    void 견적에서_남의_받는_분은_404() throws Exception {
        quote(500_000, "KRW", otherUsersRecipient())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_FOUND"));
    }

    @Test
    void 보낼_원화가_잔액보다_크면_422() throws Exception {
        quote(1_000_001, "KRW", motherId)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.details.balance.amount").value(1_000_000));
        // 역산한 원화도 잔액과 비교한다(45,000 PHP → 1,004,400 KRW)
        quote(45_000, "PHP", motherId)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void 통화_불일치_0_이하_소수는_400() throws Exception {
        quote(500_000, "USD", motherId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("currency"));
        quote(0, "KRW", motherId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("amount"));
        mockMvc.perform(get("/api/v1/transfers/quote").param("amount", "1.5").param("currency", "KRW")
                        .param("recipientId", String.valueOf(motherId)).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 필수_쿼리_값이_없으면_400() throws Exception {
        mockMvc.perform(get("/api/v1/transfers/quote").param("currency", "KRW")
                        .param("recipientId", String.valueOf(motherId)).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("amount"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("required parameter"));
    }

    // ---- INT-14·17·18 ----

    @Test
    void 내부_API는_토큰이_없으면_401() throws Exception {
        mockMvc.perform(get(INTERNAL + "/users/" + userId + "/remittance-recipients"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(INTERNAL + "/fx-rates").param("currency", "PHP"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(INTERNAL + "/users/" + userId + "/remittance-quote").param("recipientId",
                        String.valueOf(motherId)).param("amount", "500000").param("currency", "KRW"))
                .andExpect(status().isUnauthorized());
        // 앱 JWT 로는 내부 API 를 부를 수 없다
        mockMvc.perform(get(INTERNAL + "/fx-rates").param("currency", "PHP").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 내부_받는_분_목록은_계약_형식이고_화면과_같은_조건() throws Exception {
        internal(get(INTERNAL + "/users/" + userId + "/remittance-recipients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients.length()").value(2))
                .andExpect(jsonPath("$.recipients[0].recipientId").value(motherId))
                .andExpect(jsonPath("$.recipients[0].nameEn").value("LORNA REYES"))
                .andExpect(jsonPath("$.recipients[0].relationship").value("MOTHER"))
                .andExpect(jsonPath("$.recipients[0].countryCode").value("PH"))
                .andExpect(jsonPath("$.recipients[0].currency").value("PHP"))
                .andExpect(jsonPath("$.recipients[0].bankName").value("BDO"))
                .andExpect(jsonPath("$.recipients[0].accountLast4").value("5566"))
                .andExpect(jsonPath("$.recipients[1].relationship").value("SELF"));

        long empty = insertUser("test-transfer-empty", "EMPTY");
        internal(get(INTERNAL + "/users/" + empty + "/remittance-recipients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients.length()").value(0));
        internal(get(INTERNAL + "/users/999999999/remittance-recipients"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 내부_환율은_끝자리_0을_떼고_지수_표기를_쓰지_않는다() throws Exception {
        String php = internal(get(INTERNAL + "/fx-rates").param("currency", "PHP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value(1))
                .andExpect(jsonPath("$.asOf").value(RATE_DATE))
                .andReturn().getResponse().getContentAsString();
        assertThat(php).contains("\"rate\":22.32,");

        // 1350.00 은 1.35E+3 이 아니라 1350
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, "
                + "is_mock, fetched_at) VALUES ('XTS', 1, '2026-11-06', '2026-11-05 15:00:00', 1350.00, 'MOCK', TRUE, "
                + "'2026-11-06 02:05:00')");
        String xts = internal(get(INTERNAL + "/fx-rates").param("currency", "XTS"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(xts).contains("\"rate\":1350,").doesNotContain("E+");

        internal(get(INTERNAL + "/fx-rates").param("currency", "XXX"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FX_RATE_NOT_FOUND"));
    }

    @Test
    void 화면과_내부_견적은_같은_입력에_같은_결과() throws Exception {
        for (String[] input : new String[][] {{"500000", "KRW"}, {"20000", "PHP"}, {"1", "KRW"}}) {
            String screen = quote(Long.parseLong(input[0]), input[1], motherId)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String inner = internal(get(INTERNAL + "/users/" + userId + "/remittance-quote")
                            .param("recipientId", String.valueOf(motherId)).param("amount", input[0])
                            .param("currency", input[1]))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(inner).as("%s %s", input[0], input[1]).isEqualTo(screen);
        }
    }

    @Test
    void 내부_견적은_잔액을_보지_않고_남의_받는_분은_404() throws Exception {
        internal(get(INTERNAL + "/users/" + userId + "/remittance-quote").param("recipientId",
                        String.valueOf(motherId)).param("amount", "1000001").param("currency", "KRW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiveAmount.amount").value(44_802)); // 44,802.82 버림
        internal(get(INTERNAL + "/users/" + userId + "/remittance-quote").param("recipientId",
                        String.valueOf(otherUsersRecipient())).param("amount", "500000").param("currency", "KRW"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_FOUND"));
        internal(get(INTERNAL + "/users/" + userId + "/remittance-quote").param("recipientId",
                        String.valueOf(motherId)).param("amount", "500000").param("currency", "USD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---- Swagger ----

    @Test
    void Swagger_예시는_Reyes_값() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(docs).contains("/api/v1/transfers/context", "/api/v1/transfers/quote",
                "/internal/v1/ai-context/fx-rates", "/internal/v1/ai-context/users/{userId}/remittance-recipients",
                "/internal/v1/ai-context/users/{userId}/remittance-quote");
        assertThat(docs).contains("LORNA REYES", "신한 주거래 우대통장", "110-***-**4821", "22401", "22.32");
    }

    private ResultActions quote(long amount, String currency, long recipientId) throws Exception {
        return mockMvc.perform(get("/api/v1/transfers/quote").param("amount", String.valueOf(amount))
                .param("currency", currency).param("recipientId", String.valueOf(recipientId))
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions internal(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY));
    }

    private String bearerOf(long id) {
        return "Bearer " + jwtTokenProvider.issue(id);
    }

    private long insertUser(String key, String name) {
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES (?, ?)", key, name);
        return jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class, key);
    }

    private long insertRecipient(long ownerId, String key, String name, String relationship, String bank,
                                 String masked, boolean confirmed) {
        jdbc.update("INSERT INTO remittance_recipients (user_id, external_recipient_key, name_en, relationship, "
                        + "country_code, currency, bank_name, account_no_masked, source, is_mock, "
                        + "relationship_confirmed_at) VALUES (?, ?, ?, ?, 'PH', 'PHP', ?, ?, 'MOCK', TRUE, ?)",
                ownerId, key, name, relationship, bank, masked, confirmed ? "2026-11-01 00:00:00" : null);
        return jdbc.queryForObject("SELECT id FROM remittance_recipients WHERE user_id = ? "
                + "AND external_recipient_key = ?", Long.class, ownerId, key);
    }

    private void insertAccount(long ownerId, long balance) {
        jdbc.update("INSERT INTO ingestion_batches (user_id, source_type, request_key, status, started_at) "
                + "VALUES (?, 'MOCK', 'test-transfer', 'SUCCEEDED', NOW(3))", ownerId);
        Long batchId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO financial_accounts (user_id, institution_id, batch_id, external_account_key, "
                + "account_no_masked, account_type, product_name, balance, is_salary_account) VALUES (?, "
                + "(SELECT id FROM institutions WHERE institution_code = 'SHINHAN_BANK'), ?, 'test-transfer', "
                + "'110-***-**4821', 'DEMAND', '신한 주거래 우대통장', ?, TRUE)", ownerId, batchId, balance);
    }

    private long otherUsersRecipient() {
        long other = insertUser("test-transfer-other", "OTHER");
        return insertRecipient(other, "test-other-mother", "LORNA REYES", "MOTHER", "BDO", "****5566", true);
    }
}
