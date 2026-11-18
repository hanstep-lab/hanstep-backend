package com.hanstep.fx.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.fx.domain.FxSource;
import com.hanstep.fx.service.ExchangeRatesSavedEvent;
import com.hanstep.fx.service.ExchangeRatesSavedEvent.SavedRate;
import com.hanstep.fx.service.FxAlertTriggerService;
import com.hanstep.support.TestUsers;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아직 알리지 않은 도달 조회(GET notifications)·알렸음(POST notified, #129). 실제 DB, 테스트마다 롤백.
 * 환율은 ISO 4217 시험용 코드 XTS(수출입은행 형식)·XXX(시장 환율 참고값, 100 단위), 날짜는 2099년이라 다른 환율과 섞이지 않는다.
 * 테스트 트랜잭션은 롤백되므로 도달 판정의 커밋 뒤 스트림 전송(AFTER_COMMIT)은 일어나지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FxAlertNotificationApiTest {

    private static final String URL = "/api/v1/fx/alerts";
    private static final String KST_TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00";
    private static final String CREATED = "2099-01-02 01:00:00"; // 알림 만든 시각(10:00 KST)
    private static final String SAVED = "2099-01-02 02:00:00";   // 환율 행 처음 저장(11:00 KST)

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FxAlertTriggerService triggerService;

    private long userId;
    private long otherId;
    private String bearer;
    private long xtsRateId;
    private long xxxRateId;

    @BeforeEach
    void setUp() {
        userId = TestUsers.create(jdbc, "test-fx-notify", "TEST FX NOTIFY");
        otherId = TestUsers.create(jdbc, "test-fx-notify-other", "TEST FX NOTIFY OTHER");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        // 수출입은행: 고시일 2099-01-02(rate_at 00:00 KST). 시장 환율 참고값: 100 단위, 송금 환율 없음
        xtsRateId = rate("XTS", 1, "2099-01-02", "2099-01-01 15:00:00", "1300.0000", "1313.0000", "KOREAEXIM");
        xxxRateId = rate("XXX", 100, "2099-01-01", "2099-01-01 15:00:00", "5.4296", null, "MARKET_REF");
    }

    @Test
    void 도달_전_0건_도달_후_1건_알린_뒤_0건() throws Exception {
        long alertId = alert(userId, "XTS", "SEND", "LTE", "1313.5", "ACTIVE", null, null);
        notifications().andExpect(status().isOk()).andExpect(jsonPath("$.notifications.length()").value(0));

        triggerService.onRatesSaved(new ExchangeRatesSavedEvent(FxSource.KOREAEXIM, false, List.of(saved(xtsRateId))));

        notifications().andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.notifications[0].alertId").value(alertId))
                .andExpect(jsonPath("$.notifications[0].currency").value("XTS"))
                .andExpect(jsonPath("$.notifications[0].rateType").value("SEND"))
                .andExpect(jsonPath("$.notifications[0].comparator").value("LTE"))
                .andExpect(jsonPath("$.notifications[0].targetRate").value(1313.5))
                .andExpect(jsonPath("$.notifications[0].unitAmount").value(1))
                .andExpect(jsonPath("$.notifications[0].triggeredRate").value(1313))
                .andExpect(jsonPath("$.notifications[0].triggeredAt").value(matchesPattern(KST_TIME)))
                .andExpect(jsonPath("$.notifications[0].rateDate").value("2099-01-02"))
                .andExpect(jsonPath("$.notifications[0].source").value("KOREAEXIM"));
        list().andExpect(jsonPath("$.alerts[0].status").value("TRIGGERED"))
                .andExpect(jsonPath("$.alerts[0].notifiedAt").isEmpty());

        notified(alertId).andExpect(status().isNoContent());

        notifications().andExpect(status().isOk()).andExpect(jsonPath("$.notifications.length()").value(0));
        list().andExpect(jsonPath("$.alerts[0].notifiedAt").value(matchesPattern(KST_TIME)));
    }

    @Test
    void 항목은_도달_판정에_쓴_행의_단위_기준일_출처다() throws Exception {
        alert(userId, "XXX", "BASE", "GTE", "5.4", "TRIGGERED", xxxRateId, "2099-01-02 03:00:00");

        notifications().andExpect(jsonPath("$.notifications[0].unitAmount").value(100))
                .andExpect(jsonPath("$.notifications[0].triggeredRate").value(5.4296))
                .andExpect(jsonPath("$.notifications[0].rateDate").value("2099-01-01"))
                .andExpect(jsonPath("$.notifications[0].source").value("MARKET_REF"));
    }

    @Test
    void notified를_두_번_보내도_204이고_처음_시각을_그대로_둔다() throws Exception {
        long alertId = alert(userId, "XTS", "SEND", "LTE", "1400", "TRIGGERED", xtsRateId, SAVED);

        notified(alertId).andExpect(status().isNoContent());
        String first = notifiedAt(alertId);
        Thread.sleep(20);
        notified(alertId).andExpect(status().isNoContent());

        assertThat(first).isNotNull();
        assertThat(notifiedAt(alertId)).isEqualTo(first);
    }

    @Test
    void 남의_알림과_없는_알림은_404() throws Exception {
        long others = alert(otherId, "XTS", "SEND", "LTE", "1400", "TRIGGERED", xtsRateId, SAVED);

        notified(others).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        notified(Long.MAX_VALUE).andExpect(status().isNotFound());
        assertThat(notifiedAt(others)).isNull();
    }

    @Test
    void 도달_전_ACTIVE_알림에_notified는_409_INVALID_STATE() throws Exception {
        long alertId = alert(userId, "XTS", "SEND", "LTE", "1200", "ACTIVE", null, null);

        notified(alertId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.details.status").value("ACTIVE"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertThat(notifiedAt(alertId)).isNull();
    }

    @Test
    void 도달_뒤_지운_알림은_보충에_없고_notified는_409() throws Exception {
        long alertId = alert(userId, "XTS", "SEND", "LTE", "1400", "TRIGGERED", xtsRateId, SAVED);
        mockMvc.perform(delete(URL + "/" + alertId).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNoContent());

        notifications().andExpect(jsonPath("$.notifications.length()").value(0));
        notified(alertId).andExpect(status().isConflict()).andExpect(jsonPath("$.details.status").value("CANCELLED"));
    }

    @Test
    void 도달_시각_최신순이고_같으면_alertId_큰_순이며_남의_도달은_없다() throws Exception {
        long oldest = alert(userId, "XTS", "SEND", "LTE", "1400", "TRIGGERED", xtsRateId, "2099-01-02 02:00:00");
        long tieSmall = alert(userId, "XTS", "SEND", "GTE", "1200", "TRIGGERED", xtsRateId, "2099-01-02 05:00:00");
        long tieLarge = alert(userId, "XXX", "BASE", "GTE", "5", "TRIGGERED", xxxRateId, "2099-01-02 05:00:00");
        long notifiedAlready = alert(userId, "XXX", "BASE", "LTE", "6", "TRIGGERED", xxxRateId, "2099-01-02 06:00:00");
        jdbc.update("UPDATE fx_alerts SET notified_at = '2099-01-02 06:00:01' WHERE id = ?", notifiedAlready);
        alert(otherId, "XTS", "SEND", "LTE", "1400", "TRIGGERED", xtsRateId, "2099-01-02 07:00:00");

        notifications().andExpect(jsonPath("$.notifications.length()").value(3))
                .andExpect(jsonPath("$.notifications[0].alertId").value(tieLarge))
                .andExpect(jsonPath("$.notifications[1].alertId").value(tieSmall))
                .andExpect(jsonPath("$.notifications[2].alertId").value(oldest));
    }

    @Test
    void alertId가_숫자가_아니면_400() throws Exception {
        mockMvc.perform(post(URL + "/abc/notified").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("alertId"));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get(URL + "/notifications")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post(URL + "/1/notified")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/stream").accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private ResultActions notifications() throws Exception {
        return mockMvc.perform(get(URL + "/notifications").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions list() throws Exception {
        return mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions notified(long alertId) throws Exception {
        return mockMvc.perform(post(URL + "/" + alertId + "/notified").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private String notifiedAt(long alertId) {
        return jdbc.queryForObject("SELECT DATE_FORMAT(notified_at, '%Y-%m-%d %H:%i:%s.%f') FROM fx_alerts WHERE id = ?",
                String.class, alertId);
    }

    private SavedRate saved(long rateId) {
        return jdbc.queryForObject("SELECT currency, rate_date, rate_at FROM exchange_rates WHERE id = ?",
                (rs, i) -> new SavedRate(rateId, rs.getString("currency"), rs.getObject("rate_date", LocalDate.class),
                        rs.getObject("rate_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), rateId);
    }

    /** 알림 1행. 시각은 UTC. 만든 시각은 CREATED. */
    private long alert(long owner, String currency, String rateType, String comparator, String target, String status,
                       Long triggeredRateId, String triggeredAtUtc) {
        return insert("INSERT INTO fx_alerts (user_id, currency, rate_type, comparator, target_rate, status, "
                        + "triggered_rate_id, triggered_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                owner, currency, rateType, comparator, target, status, triggeredRateId, triggeredAtUtc, CREATED);
    }

    /** 환율 1행. 시각은 UTC, 처음 저장 시각은 SAVED(알림보다 늦음). */
    private long rate(String currency, int unit, String rateDate, String rateAtUtc, String base, String send,
                      String source) {
        return insert("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                        + "source, is_mock, fetched_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?)",
                currency, unit, rateDate, rateAtUtc, base, send, source, SAVED, SAVED);
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, key);
        return key.getKey().longValue();
    }
}
