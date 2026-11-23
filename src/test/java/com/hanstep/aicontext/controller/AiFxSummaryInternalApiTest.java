package com.hanstep.aicontext.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.support.TestUsers;
import org.hamcrest.Matchers;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 챗봇 환율 진단 요약(GET /internal/v1/ai-context/users/{userId}/fx-summary). 실제 DB, 테스트마다 롤백.
 * 로컬 DB 의 환율과 섞이지 않게 시험용 국적 XTL → XTS, XXL → XXX(데이터 없음)를 매핑하고 기준일은 2099년을 쓴다.
 * USD 는 2099년 행을 넣어 그 행이 최신이 되게 한다. Mock 은 읽지 않는다(기본 설정).
 */
@SpringBootTest(properties = {InternalApiTestSupport.KEY_PROPERTY,
        "hanstep.fx-rates.home-currencies[0].nationality=PHL", "hanstep.fx-rates.home-currencies[0].currency=PHP",
        "hanstep.fx-rates.home-currencies[1].nationality=XTL", "hanstep.fx-rates.home-currencies[1].currency=XTS",
        "hanstep.fx-rates.home-currencies[2].nationality=XXL", "hanstep.fx-rates.home-currencies[2].currency=XXX"})
@AutoConfigureMockMvc
@Transactional
class AiFxSummaryInternalApiTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = TestUsers.create(jdbc, "test-ai-fx-summary", "TEST AI FX SUMMARY");
        // 기존 단정은 한국어 사용자 기준(users.preferred_language 기본값은 en)
        jdbc.update("UPDATE users SET preferred_language = 'ko' WHERE id = ?", userId);
        nationality("XTL");
        // USD: 2099-03-30·03-31 수출입은행 고시. XTS: 2099-02-28~03-31 4영업일(1M 평균 가능, 3M 이상 데이터 부족)
        koreaexim("USD", "2099-03-30", "1400.0000", "1414.0000");
        koreaexim("USD", "2099-03-31", "1386.0000", "1399.8600");
        koreaexim("XTS", "2099-02-28", "1000.0000", "1010.0000");
        koreaexim("XTS", "2099-03-15", "1100.0000", "1111.0000");
        koreaexim("XTS", "2099-03-30", "1200.0000", "1212.0000");
        koreaexim("XTS", "2099-03-31", "1300.0000", "1313.0000");
    }

    @Test
    void currency를_빼면_화면_통화_전부를_환율_화면과_같은_값으로_준다() throws Exception {
        String body = summary(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.screenCurrencies").value(Matchers.contains("USD", "XTS")))
                .andExpect(jsonPath("$.unavailableCurrencies.length()").value(0))
                .andExpect(jsonPath("$.currencies[0].currency").value("USD"))
                .andExpect(jsonPath("$.currencies[0].rate").value(1386))
                .andExpect(jsonPath("$.currencies[0].change").value(-14))
                .andExpect(jsonPath("$.currencies[0].changeRate").value(-1))
                .andExpect(jsonPath("$.currencies[1].currency").value("XTS"))
                .andExpect(jsonPath("$.currencies[1].unit").value(1))
                .andExpect(jsonPath("$.currencies[1].rate").value(1300))
                .andExpect(jsonPath("$.currencies[1].sendRate").value(1313))
                .andExpect(jsonPath("$.currencies[1].asOf").value("2099-03-31"))
                .andExpect(jsonPath("$.currencies[1].source").value("KOREAEXIM"))
                .andExpect(jsonPath("$.currencies[1].sourceLabel").value("수출입은행 고시 매매기준율"))
                .andExpect(jsonPath("$.currencies[1].isMock").value(false))
                .andExpect(jsonPath("$.currencies[1].stale").value(false))
                .andExpect(jsonPath("$.currencies[1].rateAt").value("2099-03-31T00:00:00+09:00"))
                .andExpect(jsonPath("$.currencies[1].fetchedAt").value("2099-03-31T11:05:00+09:00"))
                // 1M: 02-28(포함)~03-31 4일 평균 1150, 3M 이상은 데이터 부족
                .andExpect(jsonPath("$.currencies[1].periodAverages[0].period").value("1M"))
                .andExpect(jsonPath("$.currencies[1].periodAverages[0].avg").value(1150))
                .andExpect(jsonPath("$.currencies[1].periodAverages[0].direction").value("HIGHER"))
                .andExpect(jsonPath("$.currencies[1].periodAverages[0].businessDays").value(4))
                .andExpect(jsonPath("$.currencies[1].periodAverages[1].avg").isEmpty())
                .andExpect(jsonPath("$.currencies[1].periodAverages[1].reason").value("INSUFFICIENT_DATA"))
                .andReturn().getResponse().getContentAsString();

        // 계산은 환율 화면 API 와 같다(복제하지 않고 FxRateService.rate 를 그대로 씀)
        JsonNode screen = MAPPER.readTree(mockMvc.perform(get("/api/v1/fx/rates").param("currency", "XTS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode xts = MAPPER.readTree(body).path("currencies").path(1);
        for (String field : new String[]{"rate", "sendRate", "receiveRate", "change", "changeRate", "asOf", "source",
                "isMock", "stale", "rateAt", "fetchedAt", "notices", "periodAverages"}) {
            assertThat(xts.path(field)).as(field).isEqualTo(screen.path(field));
        }
    }

    @Test
    void currency를_주면_그_통화만_주고_대소문자는_가리지_않는다() throws Exception {
        summary("xts")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencies.length()").value(1))
                .andExpect(jsonPath("$.currencies[0].currency").value("XTS"))
                .andExpect(jsonPath("$.screenCurrencies").value(Matchers.contains("USD", "XTS")));
    }

    @Test
    void 실데이터가_없는_화면_통화는_빼고_알려_주고_그_통화만_물으면_404() throws Exception {
        nationality("XXL");
        // XXX 는 Mock 행만 있다(운영에는 없음) → 실데이터 없음
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, is_mock, "
                + "fetched_at) VALUES ('XXX', 1, '2099-03-31', '2099-03-30 15:00:00', 5.0000, 'MOCK', TRUE, NOW(3))");

        summary(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenCurrencies").value(Matchers.contains("USD", "XXX")))
                .andExpect(jsonPath("$.currencies.length()").value(1))
                .andExpect(jsonPath("$.currencies[0].currency").value("USD"))
                .andExpect(jsonPath("$.unavailableCurrencies").value(Matchers.contains("XXX")));
        summary("XXX").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FX_RATE_NOT_FOUND"));
    }

    @Test
    void 참고_환율_통화는_송금_환율이_null이고_REFERENCE_RATE_ONLY() throws Exception {
        nationality("XXL");
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, provider, "
                + "is_mock, fetched_at) VALUES ('XXX', 1, '2099-03-31', '2099-03-31 14:00:00', 20.9840, 'MARKET_REF', "
                + "'frankfurter-ecb', FALSE, '2026-01-01 00:00:00')");

        summary("XXX")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencies[0].source").value("MARKET_REF"))
                .andExpect(jsonPath("$.currencies[0].sendRate").isEmpty())
                .andExpect(jsonPath("$.currencies[0].notices[0]").value("REFERENCE_RATE_ONLY"))
                // 마지막 확인이 3시간 넘게 지남
                .andExpect(jsonPath("$.currencies[0].stale").value(true))
                .andExpect(jsonPath("$.currencies[0].periodAverages[0].reason").value("INSUFFICIENT_DATA"));
    }

    @Test
    void 화면_통화가_아니면_400_없는_사용자는_404_토큰이_없으면_401() throws Exception {
        summary("JPY").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("currency"));
        mockMvc.perform(get("/internal/v1/ai-context/users/{id}/fx-summary", 999_999_999L)
                        .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mockMvc.perform(get("/internal/v1/ai-context/users/{id}/fx-summary", userId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void Swagger_예시는_Reyes_실제_출력이고_실시간_종가_표현이_없다() throws Exception {
        JsonNode docs = MAPPER.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode operation = docs.path("paths").path("/internal/v1/ai-context/users/{userId}/fx-summary").path("get");
        JsonNode example = operation.path("responses").path("200").path("content").path("application/json")
                .path("examples").path("Reyes").path("value");

        assertThat(example.path("currencies").path(0).path("currency").stringValue()).isEqualTo("PHP");
        assertThat(example.path("currencies").path(0).path("periodAverages").size()).isEqualTo(4);
        assertThat(operation.toString() + docs.path("components").path("schemas").path("AiFxCurrencySummary"))
                .doesNotContain("실시간", "종가").doesNotContainIgnoringCase("close");
    }

    private ResultActions summary(String currency) throws Exception {
        var request = get("/internal/v1/ai-context/users/{id}/fx-summary", userId)
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY);
        if (currency != null) {
            request.param("currency", currency);
        }
        return mockMvc.perform(request);
    }

    private void nationality(String code) {
        jdbc.update("INSERT INTO visa_profiles (user_id, nationality_code) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE nationality_code = VALUES(nationality_code)", userId, code);
    }

    /** 수출입은행 형식 1행. rate_at 은 기준일 00:00 KST, fetched_at 은 그날 11:05 KST(UTC 로 저장). */
    private void koreaexim(String currency, String rateDate, String base, String send) {
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                        + "receive_rate, source, is_mock, fetched_at) VALUES (?, 1, ?, ? - INTERVAL 9 HOUR, ?, ?, ?, "
                        + "'KOREAEXIM', FALSE, ? + INTERVAL 2 HOUR + INTERVAL 5 MINUTE)",
                currency, rateDate, rateDate + " 00:00:00", base, send, base, rateDate + " 00:00:00");
    }
}
