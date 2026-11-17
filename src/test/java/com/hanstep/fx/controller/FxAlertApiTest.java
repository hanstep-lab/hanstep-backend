package com.hanstep.fx.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.support.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * FX-002 목표 환율 알림. 실제 DB, 테스트마다 롤백.
 * 환율은 04 시드 대신 테스트 안에서 넣는다. ISO 4217 의 시험용 코드 XTS(송금 보낼 때 환율 있음, 수출입은행 고시 형식)와
 * 통화 없음 코드 XXX(시장 환율 참고값만, 100 단위)라 실제 환율 행과 겹치지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FxAlertApiTest {

    private static final String URL = "/api/v1/fx/alerts";
    private static final String KST_TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private String bearer;
    private long xtsLatestRateId;
    private long xxxLatestRateId;

    @BeforeEach
    void setUp() {
        userId = TestUsers.create(jdbc, "test-fx-alert", "TEST FX USER");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        rate("XTS", 1, "2026-11-05", "2026-11-04 15:00:00", "1390.0000", "1403.9000", "KOREAEXIM");
        xtsLatestRateId = rate("XTS", 1, "2026-11-06", "2026-11-05 15:00:00", "1400.0000", "1414.0000", "KOREAEXIM");
        xxxLatestRateId = rate("XXX", 100, "2026-11-06", "2026-11-06 07:00:00", "5.4296", null, "MARKET_REF");
    }

    // ---- 만들기 ----

    @Test
    void 만들면_201과_만든_알림을_준다() throws Exception {
        create("{\"currency\":\"XTS\",\"rateType\":\"SEND\",\"comparator\":\"LTE\",\"targetRate\":1350.5}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alertId").isNumber())
                .andExpect(jsonPath("$.currency").value("XTS"))
                .andExpect(jsonPath("$.rateType").value("SEND"))
                .andExpect(jsonPath("$.comparator").value("LTE"))
                .andExpect(jsonPath("$.targetRate").value(1350.5))
                .andExpect(jsonPath("$.unitAmount").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(KST_TIME)))
                .andExpect(jsonPath("$.triggeredAt").isEmpty())
                .andExpect(jsonPath("$.triggeredRate").isEmpty());

        assertThat(jdbc.queryForObject("SELECT CONCAT(currency, ':', rate_type, ':', comparator, ':', target_rate, ':', "
                + "status) FROM fx_alerts WHERE user_id = ?", String.class, userId))
                .isEqualTo("XTS:SEND:LTE:1350.5000:ACTIVE");
    }

    @Test
    void 금액과_환율은_문자열이_아닌_숫자로_준다() throws Exception {
        create("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":1400}")
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"targetRate\":1400,")))
                .andExpect(content().string(containsString("\"unitAmount\":1,")));
    }

    @Test
    void rateType을_생략하면_SEND() throws Exception {
        create("{\"currency\":\"XTS\",\"comparator\":\"GTE\",\"targetRate\":1420}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rateType").value("SEND"));
    }

    @Test
    void 고시_단위는_그_통화_최신_행_값() throws Exception {
        create("{\"currency\":\"XXX\",\"rateType\":\"BASE\",\"comparator\":\"GTE\",\"targetRate\":5.5}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rateType").value("BASE"))
                .andExpect(jsonPath("$.unitAmount").value(100));
    }

    // ---- 중복 ----

    @Test
    void 같은_통화_같은_방향의_ACTIVE가_있으면_409_DUPLICATE_REQUEST() throws Exception {
        long first = createdId("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":1350}");

        // rateType·targetRate 가 달라도 같은 통화·같은 방향이면 막는다
        create("{\"currency\":\"XTS\",\"rateType\":\"BASE\",\"comparator\":\"LTE\",\"targetRate\":1300}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"))
                .andExpect(jsonPath("$.details.alertId").value(first))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(alertCount("ACTIVE")).isEqualTo(1);
    }

    @Test
    void 방향이_다르면_LTE와_GTE를_각각_만들고_각각_중복은_409() throws Exception {
        long lte = createdId("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":1350}");
        long gte = createdId("{\"currency\":\"XTS\",\"comparator\":\"GTE\",\"targetRate\":1450}");

        create("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":1340}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.alertId").value(lte));
        create("{\"currency\":\"XTS\",\"comparator\":\"GTE\",\"targetRate\":1460}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.alertId").value(gte));
        assertThat(alertCount("ACTIVE")).isEqualTo(2);
    }

    @Test
    void 통화가_다르면_같은_방향도_만든다() throws Exception {
        createdId("{\"currency\":\"XTS\",\"rateType\":\"BASE\",\"comparator\":\"LTE\",\"targetRate\":1350}");
        create("{\"currency\":\"XXX\",\"rateType\":\"BASE\",\"comparator\":\"LTE\",\"targetRate\":5.2}")
                .andExpect(status().isCreated());
    }

    @Test
    void 지운_뒤에는_같은_조합을_다시_만든다() throws Exception {
        long first = createdId("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":1350}");
        remove(first).andExpect(status().isNoContent());

        long second = createdId("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":1350}");

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForList("SELECT CONCAT(id, ':', status) FROM fx_alerts WHERE user_id = ? ORDER BY id",
                String.class, userId)).containsExactly(first + ":CANCELLED", second + ":ACTIVE");
    }

    @Test
    void 도달한_알림이_있어도_같은_조합을_다시_만든다() throws Exception {
        alert(userId, "XTS", "SEND", "LTE", "1415", "TRIGGERED", xtsLatestRateId, "2026-11-06 01:00:00",
                "2026-11-05 00:00:00");

        create("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":1350}")
                .andExpect(status().isCreated());
    }

    @Test
    void DB도_같은_조합의_ACTIVE_두_행을_막고_TRIGGERED_CANCELLED는_막지_않는다() {
        alert(userId, "XTS", "SEND", "LTE", "1350", "ACTIVE", null, null, "2026-11-10 00:00:00");

        assertThatThrownBy(() -> alert(userId, "XTS", "BASE", "LTE", "1300", "ACTIVE", null, null,
                "2026-11-10 00:00:01")).isInstanceOf(DuplicateKeyException.class);
        alert(userId, "XTS", "SEND", "LTE", "1350", "CANCELLED", null, null, "2026-11-09 00:00:00");
        alert(userId, "XTS", "SEND", "LTE", "1350", "CANCELLED", null, null, "2026-11-08 00:00:00");
        alert(userId, "XTS", "SEND", "LTE", "1415", "TRIGGERED", xtsLatestRateId, "2026-11-06 02:05:00",
                "2026-11-05 00:00:00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fx_alerts WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(4);
    }

    // ---- 목록 ----

    @Test
    void 목록은_ACTIVE와_TRIGGERED만_최신순이고_도달한_알림은_도달_환율을_준다() throws Exception {
        long active = alert(userId, "XTS", "SEND", "GTE", "1450.0000", "ACTIVE", null, null, "2026-11-10 00:00:00");
        long triggeredSend = alert(userId, "XTS", "SEND", "LTE", "1415.0000", "TRIGGERED", xtsLatestRateId,
                "2026-11-06 02:05:00", "2026-11-11 00:00:00");
        long triggeredBase = alert(userId, "XXX", "BASE", "GTE", "5.4000", "TRIGGERED", xxxLatestRateId,
                "2026-11-06 07:02:00", "2026-11-12 00:00:00");
        alert(userId, "XXX", "BASE", "LTE", "5.0000", "CANCELLED", null, null, "2026-11-13 00:00:00");

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(3))
                .andExpect(jsonPath("$.alerts[0].alertId").value(triggeredBase))
                .andExpect(jsonPath("$.alerts[0].status").value("TRIGGERED"))
                .andExpect(jsonPath("$.alerts[0].unitAmount").value(100))
                .andExpect(jsonPath("$.alerts[0].targetRate").value(5.4))
                .andExpect(jsonPath("$.alerts[0].triggeredRate").value(5.4296))
                .andExpect(jsonPath("$.alerts[0].triggeredAt").value("2026-11-06T16:02:00+09:00"))
                .andExpect(jsonPath("$.alerts[0].createdAt").value("2026-11-12T09:00:00+09:00"))
                .andExpect(jsonPath("$.alerts[1].alertId").value(triggeredSend))
                .andExpect(jsonPath("$.alerts[1].triggeredRate").value(1414))
                .andExpect(jsonPath("$.alerts[2].alertId").value(active))
                .andExpect(jsonPath("$.alerts[2].status").value("ACTIVE"))
                .andExpect(jsonPath("$.alerts[2].triggeredAt").isEmpty())
                .andExpect(jsonPath("$.alerts[2].triggeredRate").isEmpty());
    }

    @Test
    void 알림이_없으면_빈_배열() throws Exception {
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts").isArray())
                .andExpect(jsonPath("$.alerts.length()").value(0));
    }

    // ---- 지우기 ----

    @Test
    void 지우면_204이고_목록에서_빠지며_행은_CANCELLED로_남는다() throws Exception {
        long alertId = createdId("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":1350}");

        remove(alertId).andExpect(status().isNoContent()).andExpect(content().string(""));

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.alerts.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT status FROM fx_alerts WHERE id = ?", String.class, alertId))
                .isEqualTo("CANCELLED");
    }

    @Test
    void 이미_지운_알림을_다시_지워도_204() throws Exception {
        long alertId = createdId("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":1350}");
        remove(alertId).andExpect(status().isNoContent());

        remove(alertId).andExpect(status().isNoContent());
    }

    @Test
    void 도달한_알림도_지우면_목록에서_빠진다() throws Exception {
        long alertId = alert(userId, "XTS", "SEND", "LTE", "1415", "TRIGGERED", xtsLatestRateId,
                "2026-11-06 02:05:00", "2026-11-05 00:00:00");

        remove(alertId).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT CONCAT(status, ':', triggered_rate_id) FROM fx_alerts WHERE id = ?",
                String.class, alertId)).isEqualTo("CANCELLED:" + xtsLatestRateId);
    }

    @Test
    void 남의_알림은_지우면_404이고_내_목록에도_없다() throws Exception {
        long other = TestUsers.create(jdbc, "test-fx-alert-other", "TEST FX OTHER");
        long othersAlert = alert(other, "XTS", "SEND", "LTE", "1350", "ACTIVE", null, null, "2026-11-10 00:00:00");

        remove(othersAlert)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.alerts.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT status FROM fx_alerts WHERE id = ?", String.class, othersAlert))
                .isEqualTo("ACTIVE");
    }

    @Test
    void 남의_알림이_지워진_상태여도_404() throws Exception {
        long other = TestUsers.create(jdbc, "test-fx-alert-other", "TEST FX OTHER");
        long othersAlert = alert(other, "XTS", "SEND", "LTE", "1350", "CANCELLED", null, null, "2026-11-10 00:00:00");

        remove(othersAlert).andExpect(status().isNotFound());
    }

    @Test
    void 없는_알림을_지우면_404() throws Exception {
        remove(Long.MAX_VALUE).andExpect(status().isNotFound());
    }

    @Test
    void alertId가_숫자가_아니면_400() throws Exception {
        mockMvc.perform(delete(URL + "/abc").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("alertId"));
    }

    // ---- 환율·검증 ----

    @Test
    void 환율이_저장되지_않은_통화는_404_FX_RATE_NOT_FOUND() throws Exception {
        create("{\"currency\":\"KRW\",\"rateType\":\"BASE\",\"comparator\":\"LTE\",\"targetRate\":1}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FX_RATE_NOT_FOUND"));
        assertThat(alertCount("ACTIVE")).isZero();
    }

    @Test
    void 송금_보낼_때_환율이_없는_통화에_SEND면_400과_이유() throws Exception {
        create("{\"currency\":\"XXX\",\"rateType\":\"SEND\",\"comparator\":\"LTE\",\"targetRate\":5.2}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.reason").value("SEND_RATE_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.details.fields[0].field").value("rateType"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("XXX has no send rate (MARKET_REF only), use BASE"));
        // 생략해도 SEND 라 같은 400
        create("{\"currency\":\"XXX\",\"comparator\":\"LTE\",\"targetRate\":5.2}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.reason").value("SEND_RATE_NOT_AVAILABLE"));
        assertThat(alertCount("ACTIVE")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"php\"", "\"Xts\"", "\"XT\"", "\"XTSS\"", "\"X1S\"", "\" XTS\"", "\"\"", "null"})
    void currency가_대문자_3자리가_아니면_400(String currency) throws Exception {
        create("{\"currency\":" + currency + ",\"comparator\":\"LTE\",\"targetRate\":1350}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("currency"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0000", "-0.0001", "-1", "0.00001", "1350.12345", "100000000000000", "null"})
    void targetRate가_0_이하거나_소수_5자리_이상이거나_너무_크면_400(String targetRate) throws Exception {
        create("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":" + targetRate + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("targetRate"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0001", "1350.1234", "99999999999999.9999"})
    void targetRate_경계값_안쪽은_만든다(String targetRate) throws Exception {
        create("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":" + targetRate + "}")
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"targetRate\":" + targetRate + ",")));
        assertThat(jdbc.queryForObject("SELECT target_rate FROM fx_alerts WHERE user_id = ?", BigDecimal.class,
                userId)).isEqualByComparingTo(targetRate);
    }

    @Test
    void comparator가_없으면_400() throws Exception {
        create("{\"currency\":\"XTS\",\"targetRate\":1350}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("comparator"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"comparator\":\"EQ\"", "\"comparator\":\"LTE\",\"rateType\":\"MID\""})
    void comparator나_rateType이_정해진_값이_아니면_400(String field) throws Exception {
        create("{\"currency\":\"XTS\"," + field + ",\"targetRate\":1350}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":1350}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(URL + "/1")).andExpect(status().isUnauthorized());
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long createdId(String body) throws Exception {
        String json = create(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.alertId")).longValue();
    }

    private ResultActions remove(long alertId) throws Exception {
        return mockMvc.perform(delete(URL + "/" + alertId).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private int alertCount(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fx_alerts WHERE user_id = ? AND status = ?", Integer.class,
                userId, status);
    }

    /** 환율 1행. 시각은 UTC. MARKET_REF 는 송금 환율 없이 넣는다. */
    private long rate(String currency, int unit, String rateDate, String rateAtUtc, String base, String send,
                      String source) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate,
                      source, is_mock, fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, currency);
            ps.setInt(2, unit);
            ps.setString(3, rateDate);
            ps.setString(4, rateAtUtc);
            ps.setString(5, base);
            ps.setString(6, send);
            ps.setString(7, source);
            ps.setString(8, rateAtUtc);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /** 알림 1행을 바로 넣는다(TRIGGERED·CANCELLED·만든 시각 지정용). 시각은 UTC. */
    private long alert(long owner, String currency, String rateType, String comparator, String target, String status,
                       Long triggeredRateId, String triggeredAtUtc, String createdAtUtc) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO fx_alerts (user_id, currency, rate_type, comparator, target_rate, status,
                      triggered_rate_id, triggered_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, owner);
            ps.setString(2, currency);
            ps.setString(3, rateType);
            ps.setString(4, comparator);
            ps.setString(5, target);
            ps.setString(6, status);
            ps.setObject(7, triggeredRateId);
            ps.setString(8, triggeredAtUtc);
            ps.setString(9, createdAtUtc);
            return ps;
        }, key);
        return key.getKey().longValue();
    }
}
