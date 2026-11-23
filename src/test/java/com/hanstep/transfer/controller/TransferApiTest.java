package com.hanstep.transfer.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import java.time.LocalDate;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TRF-01·02(화면)와 INT-14·17·18(챗봇 내부). 실제 DB, 테스트마다 롤백.
 * 데모 사용자 대신 테스트 사용자(국적 PHL)를 만들고, 환율도 테스트 안에서 넣는다: USD 매매기준율 1331.50(KOREAEXIM)과
 * PHP 참고 20.984(ECB). 기준일을 먼 미래(2099-01-02)로 둬서 로컬 DB 의 04 시드·수집한 실데이터보다 최신이 된다.
 * 견적 기대값은 손 계산표(resources/remittance/quote_cases_v1.csv)의 행과 같다.
 * 송금 견적 재설계(#121): 송금 통화 USD, 쏠편한·쏠빠른 비교. 기존 PHP 필드는 쏠편한 기준 참고값.
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
        // 기존 단정은 한국어 사용자 기준(users.preferred_language 기본값은 en)
        language("ko");
        motherId = insertRecipient(userId, "test-mother", "LORNA REYES", "MOTHER", "BDO", "****5566", true);
        selfId = insertRecipient(userId, "test-self", "MARK ANTHONY REYES", "SELF", "BPI", "****7788", true);
        insertRecipient(userId, "test-unconfirmed", "JUAN DELA CRUZ", "OTHER", "BPI", "****1234", false);
        insertAccount(userId, 1_000_000);
        jdbc.update("INSERT INTO visa_profiles (user_id, nationality_code) VALUES (?, 'PHL')", userId);
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                + "receive_rate, source, is_mock, fetched_at) VALUES ('USD', 1, ?, '2099-01-01 15:00:00', 1331.5000, "
                + "1344.2000, 1318.8000, 'KOREAEXIM', FALSE, '2099-01-02 02:05:00')", RATE_DATE);
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, provider, "
                + "is_mock, fetched_at) VALUES ('PHP', 1, ?, '2099-01-02 15:00:00', 20.9840, 'MARKET_REF', "
                + "'frankfurter-ecb', FALSE, '2099-01-02 15:05:00')", RATE_DATE);
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
    void 영어_사용자는_관계_목적_계좌_이름이_영어이고_나라_이름은_그대로() throws Exception {
        language("en");

        mockMvc.perform(get("/api/v1/transfers/context").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient.relation").value("Mother"))
                .andExpect(jsonPath("$.recipient.country").value("필리핀"))
                .andExpect(jsonPath("$.recipients[1].relation").value("Own account"))
                .andExpect(jsonPath("$.account.name").value("Shinhan Main Bank Preferred Account"))
                .andExpect(jsonPath("$.purpose.code").value("FAMILY_SUPPORT"))
                .andExpect(jsonPath("$.purpose.label").value("Family support"));
        mockMvc.perform(get("/api/v1/transfers/context").param("recipientId", String.valueOf(selfId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose.code").value("OWN_ACCOUNT"))
                .andExpect(jsonPath("$.purpose.label").value("Own account"));
    }

    @Test
    void 베트남어_사용자도_영어이고_모르는_계좌_이름은_원문_그대로() throws Exception {
        language("vi");
        jdbc.update("UPDATE financial_accounts SET product_name = '신한 쏠편한 입출금통장' WHERE user_id = ?", userId);

        mockMvc.perform(get("/api/v1/transfers/context").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient.relation").value("Mother"))
                .andExpect(jsonPath("$.account.name").value("신한 쏠편한 입출금통장"))
                .andExpect(jsonPath("$.purpose.label").value("Family support"));
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
    void 원화_70만원은_USD로_두_상품을_계산하고_기존_PHP_필드는_쏠편한_기준_참고값() throws Exception {
        quote(700_000, "KRW", motherId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientId").value(motherId))
                .andExpect(jsonPath("$.sendAmount.amount").value(700_000))
                .andExpect(jsonPath("$.sendAmount.currency").value("KRW"))
                // 기존 FE 필드: 이름·PHP 기준 그대로
                .andExpect(jsonPath("$.receiveAmount.amount").value(32_963)) // 쏠편한 32,963.25 PHP 내림
                .andExpect(jsonPath("$.receiveAmount.currency").value("PHP"))
                .andExpect(jsonPath("$.appliedRate.currency").value("PHP"))
                .andExpect(jsonPath("$.appliedRate.unit").value(1))
                .andExpect(jsonPath("$.appliedRate.rate").value(20.984))
                .andExpect(jsonPath("$.appliedRate.sourceLabel").value("ECB 기준환율 기반 참고값"))
                .andExpect(jsonPath("$.appliedRate.asOf").value(RATE_DATE))
                .andExpect(jsonPath("$.appliedRate.isMock").value(false))
                .andExpect(jsonPath("$.isReference").value(true))
                .andExpect(jsonPath("$.referenceNotice").value("LOCAL_PAYOUT_RATE_DIFFERS"))
                .andExpect(jsonPath("$.sendCurrency").value("USD"))
                .andExpect(jsonPath("$.defaultProduct").value("SOL_EASY"))
                .andExpect(jsonPath("$.isEstimated").value(true))
                .andExpect(jsonPath("$.rates.usdBaseRate").value(1331.5))
                .andExpect(jsonPath("$.rates.usdRateDate").value(RATE_DATE))
                .andExpect(jsonPath("$.rates.usdSource").value("KOREAEXIM"))
                .andExpect(jsonPath("$.rates.phpRefRate").value(20.984))
                .andExpect(jsonPath("$.rates.phpRateDate").value(RATE_DATE))
                .andExpect(jsonPath("$.rates.phpSource").value("frankfurter-ecb"))
                .andExpect(jsonPath("$.rates.usdPhpRefRate").value(63.4531))
                // 쏠편한(과거 1년 0회 + 이번 1건 → 우대 50%)
                .andExpect(jsonPath("$.quotes[0].product").value("SOL_EASY"))
                .andExpect(jsonPath("$.quotes[0].available").value(true))
                .andExpect(jsonPath("$.quotes[0].unavailableReason").isEmpty())
                .andExpect(jsonPath("$.quotes[0].sendAmountUsd").value(519.49))
                .andExpect(jsonPath("$.quotes[0].spreadPct").value(0.954))
                .andExpect(jsonPath("$.quotes[0].preferentialPct").value(50))
                .andExpect(jsonPath("$.quotes[0].remittanceCount1y").value(1))
                .andExpect(jsonPath("$.quotes[0].usdAppliedRate").value(1337.85))
                .andExpect(jsonPath("$.quotes[0].krwPrincipal").value(695_000))
                .andExpect(jsonPath("$.quotes[0].fxMarginKrw").value(3_299))
                .andExpect(jsonPath("$.quotes[0].sendFeeKrw").value(0))
                .andExpect(jsonPath("$.quotes[0].telegraphicFeeKrw").value(5_000))
                .andExpect(jsonPath("$.quotes[0].feeUsd").isEmpty())
                .andExpect(jsonPath("$.quotes[0].totalWithdrawKrw").value(700_000))
                .andExpect(jsonPath("$.quotes[0].recipientAmountUsd").value(519.49))
                .andExpect(jsonPath("$.quotes[0].recipientAmountPhpRef").value(32963.25))
                .andExpect(jsonPath("$.quotes[0].deliveryType").value("SWIFT_ACCOUNT"))
                .andExpect(jsonPath("$.quotes[0].notices[0]").value("INTERMEDIARY_FEE_MAY_APPLY"))
                .andExpect(jsonPath("$.quotes[0].sources[0].item").value("usdBaseRate"))
                // 쏠빠른(우대 없음, 수수료 USD 3.5 를 같은 환율로)
                .andExpect(jsonPath("$.quotes[1].product").value("SOL_FAST"))
                .andExpect(jsonPath("$.quotes[1].available").value(true))
                .andExpect(jsonPath("$.quotes[1].sendAmountUsd").value(517.25))
                .andExpect(jsonPath("$.quotes[1].preferentialPct").value(0))
                .andExpect(jsonPath("$.quotes[1].remittanceCount1y").isEmpty())
                .andExpect(jsonPath("$.quotes[1].usdAppliedRate").value(1344.2))
                .andExpect(jsonPath("$.quotes[1].krwPrincipal").value(695_288))
                .andExpect(jsonPath("$.quotes[1].fxMarginKrw").value(6_569))
                .andExpect(jsonPath("$.quotes[1].sendFeeKrw").isEmpty())
                .andExpect(jsonPath("$.quotes[1].feeUsd").value(3.5))
                .andExpect(jsonPath("$.quotes[1].totalWithdrawKrw").value(699_993))
                .andExpect(jsonPath("$.quotes[1].recipientAmountPhpRef").value(32821.12))
                .andExpect(jsonPath("$.quotes[1].deliveryType").value("WESTERN_UNION"))
                .andExpect(jsonPath("$.quotes[1].notices[0]").value("LOCAL_PAYOUT_RATE_BY_AGENT"));
    }

    @Test
    void 기존_FE_필드는_계속_나오고_숫자_타입이다() throws Exception {
        JsonNode body = json(quote(700_000, "KRW", motherId).andExpect(status().isOk()));

        assertThat(body.path("sendAmount").path("amount").isIntegralNumber()).isTrue();
        assertThat(body.path("receiveAmount").path("amount").isIntegralNumber()).isTrue();
        assertThat(body.path("appliedRate").path("rate").isNumber()).isTrue();
        assertThat(body.path("appliedRate").path("unit").isIntegralNumber()).isTrue();
        assertThat(body.path("quotes").path(0).path("sendAmountUsd").isNumber()).isTrue();
        assertThat(body.path("quotes").path(0).path("usdAppliedRate").isNumber()).isTrue();
    }

    @Test
    void recipientId를_빼면_첫_받는_분_어머니() throws Exception {
        mockMvc.perform(get("/api/v1/transfers/quote").param("amount", "700000").param("currency", "KRW")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientId").value(motherId));
    }

    @Test
    void 우대_구간은_기준일_포함_과거_365일_해외송금_건수와_이번_건으로_정한다() throws Exception {
        LocalDate today = LocalDate.now(KstDates.KST);
        for (int i = 0; i < 9; i++) {
            insertTransaction("remit-" + i, today.minusDays(i * 40L), "EXPENSE", "REMITTANCE", "NORMAL");
        }
        // 세지 않는 거래: 365일 전, 입금, 본인 계좌 이체 종류, 다른 분류
        insertTransaction("remit-old", today.minusDays(365), "EXPENSE", "REMITTANCE", "NORMAL");
        insertTransaction("remit-income", today, "INCOME", "REMITTANCE", "NORMAL");
        insertTransaction("remit-own", today, "EXPENSE", "REMITTANCE", "OWN_TRANSFER");
        insertTransaction("food", today, "EXPENSE", "FOOD", "NORMAL");

        // 9 + 이번 1건 = 10 → 9회 이상 70%(손 계산표 700000,9 행)
        quote(700_000, "KRW", motherId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotes[0].remittanceCount1y").value(10))
                .andExpect(jsonPath("$.quotes[0].preferentialPct").value(70))
                .andExpect(jsonPath("$.quotes[0].usdAppliedRate").value(1335.31))
                .andExpect(jsonPath("$.quotes[0].sendAmountUsd").value(520.47))
                .andExpect(jsonPath("$.quotes[0].totalWithdrawKrw").value(699_989))
                .andExpect(jsonPath("$.quotes[1].usdAppliedRate").value(1344.2));
    }

    @Test
    void 수수료보다_작은_금액은_두_상품_모두_AMOUNT_TOO_SMALL() throws Exception {
        quote(4_700, "KRW", motherId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotes[0].available").value(false))
                .andExpect(jsonPath("$.quotes[0].unavailableReason").value("AMOUNT_TOO_SMALL"))
                .andExpect(jsonPath("$.quotes[0].sendAmountUsd").isEmpty())
                .andExpect(jsonPath("$.quotes[1].unavailableReason").value("AMOUNT_TOO_SMALL"))
                .andExpect(jsonPath("$.receiveAmount").isEmpty());
    }

    @Test
    void USD_매매기준율_실데이터가_없으면_404_Mock_행은_쓰지_않는다() throws Exception {
        deleteRates("currency = 'USD' AND source = 'KOREAEXIM' AND NOT is_mock");
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                + "receive_rate, source, is_mock, fetched_at) VALUES ('USD', 1, '2099-01-03', '2099-01-02 15:00:00', "
                + "1400.0000, 1414.0000, 1386.0000, 'KOREAEXIM', TRUE, '2099-01-03 02:05:00')");

        quote(700_000, "KRW", motherId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FX_RATE_NOT_FOUND"));
    }

    @Test
    void 국적이_필리핀이_아니면_USD_견적만_내고_PHP_참고값은_null() throws Exception {
        jdbc.update("UPDATE visa_profiles SET nationality_code = 'VNM' WHERE user_id = ?", userId);

        quote(700_000, "KRW", motherId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiveAmount").isEmpty())
                .andExpect(jsonPath("$.appliedRate").isEmpty())
                .andExpect(jsonPath("$.referenceNotice").value("PHP_REF_RATE_UNAVAILABLE"))
                .andExpect(jsonPath("$.rates.usdBaseRate").value(1331.5))
                .andExpect(jsonPath("$.rates.phpRefRate").isEmpty())
                .andExpect(jsonPath("$.quotes[0].sendAmountUsd").value(519.49))
                .andExpect(jsonPath("$.quotes[0].recipientAmountPhpRef").isEmpty())
                .andExpect(jsonPath("$.quotes[0].notices[1]").value("PHP_REF_RATE_UNAVAILABLE"));
    }

    @Test
    void PHP_참고_환율이_없어도_USD_견적은_나온다() throws Exception {
        deleteRates("currency = 'PHP' AND source = 'MARKET_REF' AND NOT is_mock");

        quote(700_000, "KRW", motherId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiveAmount").isEmpty())
                .andExpect(jsonPath("$.referenceNotice").value("PHP_REF_RATE_UNAVAILABLE"))
                .andExpect(jsonPath("$.quotes[1].sendAmountUsd").value(517.25));
    }

    @Test
    void 견적에서_남의_받는_분은_404() throws Exception {
        quote(700_000, "KRW", otherUsersRecipient())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_FOUND"));
    }

    @Test
    void 출금할_원화가_잔액보다_크면_422() throws Exception {
        quote(1_000_001, "KRW", motherId)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.details.sendAmount.amount").value(1_000_001))
                .andExpect(jsonPath("$.details.balance.amount").value(1_000_000));
    }

    @Test
    void 통화가_KRW가_아니거나_0_이하_소수는_400() throws Exception {
        // 받을 PHP 금액·송금 USD 금액 입력은 받지 않는다
        for (String currency : new String[] {"PHP", "USD"}) {
            quote(20_000, currency, motherId)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details.fields[0].field").value("currency"))
                    .andExpect(jsonPath("$.details.fields[0].reason").value("must be KRW"));
        }
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
        assertThat(php).contains("\"rate\":20.984,");

        // 1350.00 은 1.35E+3 이 아니라 1350
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, "
                + "is_mock, fetched_at) VALUES ('XTS', 1, '2026-11-06', '2026-11-05 15:00:00', 1350.00, 'KOREAEXIM', "
                + "FALSE, '2026-11-06 02:05:00')");
        String xts = internal(get(INTERNAL + "/fx-rates").param("currency", "XTS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceLabel").value("수출입은행 고시 매매기준율"))
                .andReturn().getResponse().getContentAsString();
        assertThat(xts).contains("\"rate\":1350,").doesNotContain("E+");

        internal(get(INTERNAL + "/fx-rates").param("currency", "XXX"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FX_RATE_NOT_FOUND"));
    }

    @Test
    void 내부_환율은_환율_화면과_같은_조회로_Mock을_읽지_않고_우선_원천을_쓴다() throws Exception {
        // #123: 통화별 최신 값 조회를 ExchangeRateReader 하나로 모았다. Mock 만 있으면 404(운영은 실데이터만)
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, "
                + "is_mock, fetched_at) VALUES ('XXX', 1, '2099-01-03', '2099-01-02 15:00:00', 9.9000, 'MOCK', TRUE, "
                + "'2099-01-03 02:05:00')");
        internal(get(INTERNAL + "/fx-rates").param("currency", "XXX"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FX_RATE_NOT_FOUND"));

        // 더 늦은 MARKET_REF 가 있어도 KOREAEXIM 이 우선(이전에는 날짜가 늦은 행이었음)
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                + "receive_rate, source, is_mock, fetched_at) VALUES ('XTS', 1, '2099-01-02', '2099-01-01 15:00:00', "
                + "1300.0000, 1313.0000, 1287.0000, 'KOREAEXIM', FALSE, '2099-01-02 02:05:00')");
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, provider, "
                + "is_mock, fetched_at) VALUES ('XTS', 1, '2099-01-05', '2099-01-05 15:00:00', 9999.0000, 'MARKET_REF', "
                + "'frankfurter-ecb', FALSE, '2099-01-05 15:05:00')");
        internal(get(INTERNAL + "/fx-rates").param("currency", "XTS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(1300))
                .andExpect(jsonPath("$.asOf").value("2099-01-02"));
    }

    @Test
    void 화면과_내부_견적은_같은_입력에_같은_결과() throws Exception {
        for (String amount : new String[] {"700000", "32000", "4700"}) {
            String screen = quote(Long.parseLong(amount), "KRW", motherId)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String inner = internal(get(INTERNAL + "/users/" + userId + "/remittance-quote")
                            .param("recipientId", String.valueOf(motherId)).param("amount", amount)
                            .param("currency", "KRW"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(inner).as(amount).isEqualTo(screen);
        }
    }

    @Test
    void 영어_사용자는_화면과_내부_견적_모두_참고_환율_출처가_영어이고_서로_같다() throws Exception {
        language("en");

        String screen = quote(700_000, "KRW", motherId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedRate.sourceLabel").value("Reference rate based on the ECB rate"))
                .andExpect(jsonPath("$.receiveAmount.amount").value(32_963))
                .andReturn().getResponse().getContentAsString();
        String inner = internal(get(INTERNAL + "/users/" + userId + "/remittance-quote")
                        .param("recipientId", String.valueOf(motherId)).param("amount", "700000")
                        .param("currency", "KRW"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(inner).isEqualTo(screen);
    }

    @Test
    void 내부_견적은_잔액을_보지_않고_쏠빠른_한도를_넘으면_그_상품만_못_보낸다() throws Exception {
        // 잔액 1,000,000 보다 크지만 챗봇 견적은 잔액을 보지 않는다. 쏠빠른 5,000.01 USD(손 계산표 6725719 행)
        internal(get(INTERNAL + "/users/" + userId + "/remittance-quote").param("recipientId",
                        String.valueOf(motherId)).param("amount", "6725719").param("currency", "KRW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotes[0].available").value(true))
                .andExpect(jsonPath("$.quotes[0].sendAmountUsd").value(5016.04))
                .andExpect(jsonPath("$.quotes[0].sendFeeKrw").value(10_000))
                .andExpect(jsonPath("$.quotes[1].available").value(false))
                .andExpect(jsonPath("$.quotes[1].unavailableReason").value("LIMIT_EXCEEDED"));
        internal(get(INTERNAL + "/users/" + userId + "/remittance-quote").param("recipientId",
                        String.valueOf(otherUsersRecipient())).param("amount", "700000").param("currency", "KRW"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_FOUND"));
        internal(get(INTERNAL + "/users/" + userId + "/remittance-quote").param("recipientId",
                        String.valueOf(motherId)).param("amount", "20000").param("currency", "PHP"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 내부_견적은_챗봇_계약의_필드를_같은_이름과_타입으로_준다() throws Exception {
        // hanstep-AI remittance_tools.Quote·_quote_matches 가 읽는 필드(모르는 필드는 무시)
        JsonNode quote = json(internal(get(INTERNAL + "/users/" + userId + "/remittance-quote")
                .param("recipientId", String.valueOf(motherId)).param("amount", "700000").param("currency", "KRW"))
                .andExpect(status().isOk()));

        assertThat(quote.path("recipientId").asLong()).isEqualTo(motherId);
        assertThat(quote.path("sendAmount").path("amount").isIntegralNumber()).isTrue();
        assertThat(quote.path("sendAmount").path("amount").asLong()).isEqualTo(700_000); // 요청 금액 그대로
        assertThat(quote.path("sendAmount").path("currency").stringValue()).isEqualTo("KRW");
        assertThat(quote.path("receiveAmount").path("amount").isIntegralNumber()).isTrue();
        assertThat(quote.path("receiveAmount").path("amount").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(quote.path("receiveAmount").path("currency").stringValue()).isEqualTo("PHP"); // 받는 분 통화
        JsonNode rate = quote.path("appliedRate");
        assertThat(rate.path("currency").stringValue()).isEqualTo("PHP");
        assertThat(rate.path("unit").isIntegralNumber()).isTrue();
        assertThat(rate.path("rate").isNumber()).isTrue();
        assertThat(rate.path("rate").decimalValue()).isPositive();
        assertThat(rate.path("sourceLabel").stringValue()).isNotBlank();
        assertThat(LocalDate.parse(rate.path("asOf").stringValue())).isEqualTo(LocalDate.parse(RATE_DATE));
        assertThat(rate.path("isMock").isBoolean()).isTrue();
    }

    // ---- Swagger ----

    @Test
    void Swagger_예시는_Reyes_값() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(docs).contains("/api/v1/transfers/context", "/api/v1/transfers/quote",
                "/internal/v1/ai-context/fx-rates", "/internal/v1/ai-context/users/{userId}/remittance-recipients",
                "/internal/v1/ai-context/users/{userId}/remittance-quote");
        assertThat(docs).contains("LORNA REYES", "신한 주거래 우대통장", "110-***-**4821", "Reyes 700,000원",
                "\"product\":\"SOL_FAST\"", "usdAppliedRate", "LOCAL_PAYOUT_RATE_DIFFERS");
        assertThat(docs).doesNotContain("신한은행 환율");
    }

    private ResultActions quote(long amount, String currency, long recipientId) throws Exception {
        return mockMvc.perform(get("/api/v1/transfers/quote").param("amount", String.valueOf(amount))
                .param("currency", currency).param("recipientId", String.valueOf(recipientId))
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /**
     * 환율 행을 지운다(테스트 끝나면 롤백). 로컬 DB 에 도달한 알림(#125)이 그 행을 가리키면 FK(fk_fx_alerts_rate)에 걸리므로
     * 먼저 그 참조만 떼어 낸다.
     */
    private void deleteRates(String where) {
        jdbc.update("UPDATE fx_alerts SET triggered_rate_id = NULL WHERE triggered_rate_id IN "
                + "(SELECT id FROM exchange_rates WHERE " + where + ")");
        jdbc.update("DELETE FROM exchange_rates WHERE " + where);
    }

    private ResultActions internal(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY));
    }

    private String bearerOf(long id) {
        return "Bearer " + jwtTokenProvider.issue(id);
    }

    private void language(String language) {
        jdbc.update("UPDATE users SET preferred_language = ? WHERE id = ?", language, userId);
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

    /** insertAccount 로 만든 계좌의 거래 1건. */
    private void insertTransaction(String key, LocalDate bookedDate, String direction, String category, String kind) {
        jdbc.update("INSERT INTO financial_transactions (user_id, account_id, batch_id, external_txn_key, transacted_at, "
                + "booked_date, direction, amount, counterparty_name, category_code, transaction_kind) "
                + "SELECT a.user_id, a.id, a.batch_id, ?, ?, ?, ?, 500000, 'LORNA REYES', ?, ? FROM financial_accounts a "
                + "WHERE a.user_id = ? AND a.external_account_key = 'test-transfer'", key, bookedDate.atStartOfDay(),
                bookedDate, direction, category, kind, userId);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return JsonMapper.builder().build().readTree(result.andReturn().getResponse().getContentAsString());
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
