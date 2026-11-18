package com.hanstep.fx.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.support.TestUsers;
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

/** 시연·관리용 다시 판정(POST /internal/v1/fx/alerts/evaluate). 실제 DB, 테스트마다 롤백. 환율은 시험용 코드 XTS·XXX. */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class FxAlertInternalApiTest {

    private static final String URL = "/internal/v1/fx/alerts/evaluate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = TestUsers.create(jdbc, "test-fx-alert-evaluate", "TEST FX EVALUATE");
        // 최신 값(KOREAEXIM)은 2099-01-03 08:30 KST 에 처음 저장돼 알림(09:00 KST 만듦)보다 이르다. 더 늦은 MARKET_REF 보다
        // KOREAEXIM 이 우선
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                + "receive_rate, source, is_mock, fetched_at, created_at) VALUES ('XTS', 1, '2099-01-03', "
                + "'2099-01-02 15:00:00', 1300.0000, 1313.0000, 1287.0000, 'KOREAEXIM', FALSE, '2099-01-02 23:30:00', "
                + "'2099-01-02 23:30:00')");
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, provider, "
                + "is_mock, fetched_at, created_at) VALUES ('XTS', 1, '2099-01-04', '2099-01-04 15:00:00', 9999.0000, "
                + "'MARKET_REF', 'frankfurter-ecb', FALSE, '2099-01-04 15:05:00', '2099-01-04 15:05:00')");
    }

    @Test
    void 기본은_알림보다_먼저_있던_최신_값으로_바꾸지_않고_ignoreCreatedAt이면_도달시킨다() throws Exception {
        long alertId = alert("SEND", "LTE", "1313");
        long rateId = jdbc.queryForObject("SELECT id FROM exchange_rates WHERE currency = 'XTS' AND source = 'KOREAEXIM' "
                + "AND rate_date = '2099-01-03'", Long.class);

        evaluate("XTS", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignoreCreatedAt").value(false))
                .andExpect(jsonPath("$.triggered").value(0))
                .andExpect(jsonPath("$.currencies[0].currency").value("XTS"))
                .andExpect(jsonPath("$.currencies[0].rateId").value(rateId))
                .andExpect(jsonPath("$.currencies[0].alerts[0].alertId").value(alertId))
                .andExpect(jsonPath("$.currencies[0].alerts[0].result").value("RATE_BEFORE_CREATED"));

        String body = evaluate("XTS", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggered").value(1))
                .andExpect(jsonPath("$.currencies[0].rateAt").value("2099-01-03T00:00:00+09:00"))
                .andExpect(jsonPath("$.currencies[0].savedAt").value("2099-01-03T08:30:00+09:00"))
                .andExpect(jsonPath("$.currencies[0].baseRate").value(1300))
                .andExpect(jsonPath("$.currencies[0].sendRate").value(1313))
                .andExpect(jsonPath("$.currencies[0].alerts[0].result").value("TRIGGERED"))
                .andExpect(jsonPath("$.currencies[0].alerts[0].value").value(1313))
                .andReturn().getResponse().getContentAsString();
        // 끝자리 0 제거(1313.0000 이 아니라 1313)
        assertThat(body).contains("\"baseRate\":1300,", "\"value\":1313}");

        mockMvc.perform(get("/api/v1/fx/alerts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(userId)))
                .andExpect(jsonPath("$.alerts[0].status").value("TRIGGERED"))
                .andExpect(jsonPath("$.alerts[0].triggeredRate").value(1313));

        // TRIGGERED 는 다시 판정 대상이 아니다
        evaluate("XTS", true).andExpect(jsonPath("$.triggered").value(0))
                .andExpect(jsonPath("$.currencies[0].alerts.length()").value(0));
    }

    @Test
    void 기본은_fetched_at만_갱신된_행이면_대상이_아니고_알림_뒤에_처음_저장된_행이면_대상이다() throws Exception {
        long alertId = alert("SEND", "LTE", "2000");
        // 알림 뒤 1시간 수집에서 fetched_at 만 갱신됨 → 대상 아님
        jdbc.update("UPDATE exchange_rates SET fetched_at = '2099-01-03 02:05:00' WHERE currency = 'XTS' "
                + "AND source = 'KOREAEXIM'");
        evaluate("XTS", false).andExpect(jsonPath("$.currencies[0].alerts[0].result").value("RATE_BEFORE_CREATED"));

        // 다음 고시값이 알림 뒤(2099-01-04 11:00 KST)에 처음 저장됨 → 대상(rate_at 은 고시일 00:00)
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                + "receive_rate, source, is_mock, fetched_at, created_at) VALUES ('XTS', 1, '2099-01-04', "
                + "'2099-01-03 15:00:00', 1290.0000, 1302.9000, 1277.1000, 'KOREAEXIM', FALSE, '2099-01-04 02:00:00', "
                + "'2099-01-04 02:00:00')");
        evaluate("XTS", false)
                .andExpect(jsonPath("$.triggered").value(1))
                .andExpect(jsonPath("$.currencies[0].savedAt").value("2099-01-04T11:00:00+09:00"))
                .andExpect(jsonPath("$.currencies[0].alerts[0].alertId").value(alertId))
                .andExpect(jsonPath("$.currencies[0].alerts[0].result").value("TRIGGERED"))
                .andExpect(jsonPath("$.currencies[0].alerts[0].value").value(1302.9));
    }

    @Test
    void 닿지_않으면_NOT_REACHED이고_ACTIVE로_남는다() throws Exception {
        alert("BASE", "GTE", "1300.0001");

        evaluate("XTS", true).andExpect(jsonPath("$.currencies[0].alerts[0].result").value("NOT_REACHED"))
                .andExpect(jsonPath("$.currencies[0].alerts[0].value").isEmpty());
    }

    @Test
    void Mock_만_있는_통화는_404이고_잘못된_통화는_400_토큰이_없으면_401() throws Exception {
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, is_mock, "
                + "fetched_at) VALUES ('XXX', 1, '2099-01-03', '2099-01-02 15:00:00', 5.0000, 'MOCK', TRUE, NOW(3))");

        evaluate("XXX", true).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FX_RATE_NOT_FOUND"));
        evaluate("xts", true).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("currency"));
        mockMvc.perform(post(URL).param("currency", "XTS")).andExpect(status().isUnauthorized());
    }

    @Test
    void currency를_빼면_ACTIVE_알림이_있는_통화를_모두_본다() throws Exception {
        alert("BASE", "LTE", "2000");

        evaluate(null, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencies[?(@.currency == 'XTS')].alerts[0].result").value("TRIGGERED"));
    }

    private ResultActions evaluate(String currency, boolean ignoreCreatedAt) throws Exception {
        var request = post(URL).param("ignoreCreatedAt", String.valueOf(ignoreCreatedAt))
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY);
        if (currency != null) {
            request.param("currency", currency);
        }
        return mockMvc.perform(request);
    }

    private long alert(String rateType, String comparator, String target) {
        jdbc.update("INSERT INTO fx_alerts (user_id, currency, rate_type, comparator, target_rate, created_at) "
                + "VALUES (?, 'XTS', ?, ?, ?, '2099-01-03 00:00:00')", userId, rateType, comparator, target);
        return jdbc.queryForObject("SELECT MAX(id) FROM fx_alerts WHERE user_id = ?", Long.class, userId);
    }
}
