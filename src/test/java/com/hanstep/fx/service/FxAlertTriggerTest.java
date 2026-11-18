package com.hanstep.fx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.fx.domain.FxComparator;
import com.hanstep.fx.domain.FxSource;
import com.hanstep.fx.service.ExchangeRatesSavedEvent.SavedRate;
import com.hanstep.support.TestUsers;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목표 환율 알림 도달 판정(#125). 실제 DB, 테스트마다 롤백. 환율은 ISO 4217 시험용 코드 XTS·XXX, 날짜는 2099년이라
 * 로컬 DB 의 다른 환율과 섞이지 않는다. 알림은 2099-01-02 00:00 UTC 에 만든 것으로 넣는다.
 * 같은 사용자·통화·방향의 ACTIVE 는 1건이라(uq_fx_alerts_active) 목표가 다른 알림은 사용자를 나눈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FxAlertTriggerTest {

    private static final String CREATED = "2099-01-02 00:00:00";
    private static final String AFTER = "2099-01-02 15:00:00";

    @Autowired
    private FxAlertTriggerService service;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private final List<Long> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        for (int i = 0; i < 3; i++) {
            users.add(TestUsers.create(jdbc, "test-fx-alert-trigger-" + i, "TEST FX TRIGGER " + i));
        }
    }

    @Test
    void LTE는_목표와_같은_값에서_도달하고_목표보다_크면_그대로다() {
        long equal = alert(users.get(0), "XTS", "SEND", "LTE", "1313.0000");
        long below = alert(users.get(1), "XTS", "SEND", "LTE", "1312.9999");
        long rateId = koreaexim("XTS", AFTER, "1300.0000", "1313.0000", false);

        saved(rateId);

        assertThat(state(equal)).isEqualTo("TRIGGERED:" + rateId);
        assertThat(state(below)).isEqualTo("ACTIVE:null");
    }

    @Test
    void GTE는_목표와_같은_값에서_도달하고_목표보다_작으면_그대로다() {
        long equal = alert(users.get(0), "XTS", "BASE", "GTE", "1300");
        long above = alert(users.get(1), "XTS", "BASE", "GTE", "1300.0001");
        long rateId = koreaexim("XTS", AFTER, "1300.0000", "1313.0000", false);

        saved(rateId);

        assertThat(state(equal)).isEqualTo("TRIGGERED:" + rateId);
        assertThat(state(above)).isEqualTo("ACTIVE:null");
    }

    @Test
    void 송금_환율이_없는_원천이면_SEND는_판정하지_않고_BASE는_판정한다() {
        long send = alert(users.get(0), "XXX", "SEND", "LTE", "30");
        long base = alert(users.get(1), "XXX", "BASE", "LTE", "30");
        long rateId = marketRef("XXX", AFTER, "20.9840", false);

        saved(rateId);

        assertThat(state(send)).isEqualTo("ACTIVE:null");
        assertThat(state(base)).isEqualTo("TRIGGERED:" + rateId);
    }

    @Test
    void Mock_행으로는_판정하지_않는다() {
        long alertId = alert(users.get(0), "XTS", "BASE", "LTE", "2000");
        // USD Mock 행처럼 source 가 KOREAEXIM 이어도 is_mock 이면 보지 않는다
        long mockExim = koreaexim("XTS", AFTER, "1300.0000", "1313.0000", true);
        long mockSource = marketRef("XTS", "2099-01-03 15:00:00", "1300.0000", true);

        saved(mockExim, mockSource);

        assertThat(state(alertId)).isEqualTo("ACTIVE:null");
    }

    @Test
    void 알림을_만든_시각보다_이전_rate_at은_보지_않고_같은_시각부터_본다() {
        long before = alert(users.get(0), "XTS", "BASE", "LTE", "2000");
        long rateId = koreaexim("XTS", "2099-01-01 23:59:59", "1300.0000", "1313.0000", false);

        saved(rateId);
        assertThat(state(before)).isEqualTo("ACTIVE:null");

        long sameTime = koreaexim("XTS", CREATED, "1301.0000", "1314.0000", false);
        saved(sameTime);
        assertThat(state(before)).isEqualTo("TRIGGERED:" + sameTime);
    }

    @Test
    void 같은_알림은_한_번만_도달하고_그_뒤_값으로_다시_판정하지_않는다() {
        long alertId = alert(users.get(0), "XTS", "SEND", "LTE", "1400");
        long first = koreaexim("XTS", AFTER, "1300.0000", "1313.0000", false);
        saved(first);
        String triggeredAt = jdbc.queryForObject("SELECT triggered_at FROM fx_alerts WHERE id = ?", String.class,
                alertId);

        saved(first);
        long second = koreaexim("XTS", "2099-01-03 15:00:00", "1200.0000", "1212.0000", false);
        saved(second);

        assertThat(state(alertId)).isEqualTo("TRIGGERED:" + first);
        assertThat(jdbc.queryForObject("SELECT triggered_at FROM fx_alerts WHERE id = ?", String.class, alertId))
                .isEqualTo(triggeredAt);
    }

    @Test
    void 다른_통화와_지운_알림은_판정하지_않는다() {
        long other = alert(users.get(0), "XXX", "BASE", "LTE", "2000");
        long cancelled = alert(users.get(1), "XTS", "BASE", "LTE", "2000");
        jdbc.update("UPDATE fx_alerts SET status = 'CANCELLED' WHERE id = ?", cancelled);
        long rateId = koreaexim("XTS", AFTER, "1300.0000", "1313.0000", false);

        saved(rateId);

        assertThat(state(other)).isEqualTo("ACTIVE:null");
        assertThat(state(cancelled)).isEqualTo("CANCELLED:null");
    }

    @Test
    void 여러_행이_오면_rate_at이_이른_행부터_보고_처음_닿은_행을_쓴다() {
        long alertId = alert(users.get(0), "XTS", "BASE", "LTE", "1300");
        // 저장 순서와 상관없이 rate_at 순서로 본다
        long late = koreaexim("XTS", "2099-01-05 15:00:00", "1250.0000", "1262.5000", false);
        long notReached = koreaexim("XTS", AFTER, "1350.0000", "1363.5000", false);
        long reached = koreaexim("XTS", "2099-01-03 15:00:00", "1290.0000", "1302.9000", false);

        service.onRatesSaved(new ExchangeRatesSavedEvent(FxSource.KOREAEXIM, true,
                List.of(savedRate(late), savedRate(notReached), savedRate(reached))));

        assertThat(state(alertId)).isEqualTo("TRIGGERED:" + reached);
    }

    @Test
    void 수집기의_저장됨_이벤트로_도달하면_목록에_TRIGGERED와_도달_환율이_나온다() throws Exception {
        long alertId = alert(users.get(0), "XTS", "SEND", "LTE", "1313.5");
        long rateId = koreaexim("XTS", AFTER, "1300.0000", "1313.0000", false);

        publisher.publishEvent(new ExchangeRatesSavedEvent(FxSource.KOREAEXIM, false, List.of(savedRate(rateId))));

        mockMvc.perform(get("/api/v1/fx/alerts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(users.get(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[0].alertId").value(alertId))
                .andExpect(jsonPath("$.alerts[0].status").value("TRIGGERED"))
                .andExpect(jsonPath("$.alerts[0].triggeredRate").value(1313))
                .andExpect(jsonPath("$.alerts[0].triggeredAt").isNotEmpty());
    }

    @Test
    void 도달_비교는_BigDecimal_크기로_본다() {
        assertThat(FxAlertTriggerService.reached(new BigDecimal("1300.0000"),
                FxComparator.LTE, new BigDecimal("1300"))).isTrue();
        assertThat(FxAlertTriggerService.reached(new BigDecimal("1300.0001"),
                FxComparator.LTE, new BigDecimal("1300"))).isFalse();
        assertThat(FxAlertTriggerService.reached(new BigDecimal("1300"),
                FxComparator.GTE, new BigDecimal("1300.0000"))).isTrue();
        assertThat(FxAlertTriggerService.reached(new BigDecimal("1299.9999"),
                FxComparator.GTE, new BigDecimal("1300"))).isFalse();
    }

    private void saved(long... rateIds) {
        List<SavedRate> rates = new ArrayList<>();
        for (long id : rateIds) {
            rates.add(savedRate(id));
        }
        service.onRatesSaved(new ExchangeRatesSavedEvent(FxSource.KOREAEXIM, false, rates));
    }

    private SavedRate savedRate(long rateId) {
        return jdbc.queryForObject("SELECT currency, rate_date, rate_at FROM exchange_rates WHERE id = ?",
                (rs, i) -> new SavedRate(rateId, rs.getString("currency"), rs.getObject("rate_date", LocalDate.class),
                        rs.getObject("rate_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), rateId);
    }

    /** "상태:triggered_rate_id". */
    private String state(long alertId) {
        return jdbc.queryForObject("SELECT CONCAT(status, ':', IFNULL(triggered_rate_id, 'null')) FROM fx_alerts "
                + "WHERE id = ?", String.class, alertId);
    }

    private long alert(long owner, String currency, String rateType, String comparator, String target) {
        return insert("INSERT INTO fx_alerts (user_id, currency, rate_type, comparator, target_rate, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", owner, currency, rateType, comparator, target, CREATED);
    }

    /** 수출입은행 형식(송금 환율 있음). rate_date 는 rate_at(UTC) + 9시간의 날짜. */
    private long koreaexim(String currency, String rateAtUtc, String base, String send, boolean mock) {
        return insert("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                        + "receive_rate, source, is_mock, fetched_at) VALUES (?, 1, DATE(? + INTERVAL 9 HOUR), ?, ?, ?, ?, "
                        + "'KOREAEXIM', ?, ?)", currency, rateAtUtc, rateAtUtc, base, send, base, mock, rateAtUtc);
    }

    /** ECB 참고 환율 형식(송금 환율 없음). */
    private long marketRef(String currency, String rateAtUtc, String base, boolean mock) {
        return insert("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, provider, "
                        + "is_mock, fetched_at) VALUES (?, 1, DATE(?), ?, ?, ?, 'frankfurter-ecb', ?, ?)",
                currency, rateAtUtc, rateAtUtc, base, mock ? "MOCK" : "MARKET_REF", mock, rateAtUtc);
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
