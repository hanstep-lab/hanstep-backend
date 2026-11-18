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
 * 로컬 DB 의 다른 환율과 섞이지 않는다. 알림은 2099-01-02 10:00 KST 에 만들고, 환율 행은 기본으로 그날 11:00 KST 에
 * 처음 저장한 것(created_at)으로 넣는다. 수출입은행 rate_at 은 고시일 00:00 KST(알림보다 이름)다.
 * 같은 사용자·통화·방향의 ACTIVE 는 1건이라(uq_fx_alerts_active) 목표가 다른 알림은 사용자를 나눈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FxAlertTriggerTest {

    private static final String CREATED = "2099-01-02 01:00:00"; // 알림 만든 시각(10:00 KST)
    private static final String SAVED = "2099-01-02 02:00:00";   // 환율 행 처음 저장(11:00 KST)
    private static final String AFTER = "2099-01-01 15:00:00";   // 고시일 2099-01-02 00:00 KST

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
        long mockSource = marketRef("XTS", "2099-01-02 15:00:00", "1300.0000", true);

        saved(mockExim, mockSource);

        assertThat(state(alertId)).isEqualTo("ACTIVE:null");
    }

    @Test
    void 오전에_만든_알림은_그날_고시값이_11시에_처음_저장되면_판정한다() {
        // 고시일 00:00 KST(rate_at)는 알림(10:00)보다 이르지만, 행을 처음 저장한 시각(11:00)이 알림 뒤라 판정 대상
        long alertId = alert(users.get(0), "XTS", "SEND", "LTE", "1400");
        long rateId = koreaexim("XTS", "2099-01-01 15:00:00", "1300.0000", "1313.0000", false, SAVED);

        saved(rateId);

        assertThat(state(alertId)).isEqualTo("TRIGGERED:" + rateId);
    }

    @Test
    void 알림보다_먼저_저장된_행은_fetched_at이_갱신되거나_값이_바뀌어도_판정하지_않는다() {
        long alertId = alert(users.get(0), "XTS", "SEND", "LTE", "1400");
        // 09:00 KST 에 처음 저장된 행. 11:00 수집에서 fetched_at 이 갱신되고 값이 바뀌어도(UPDATED 이벤트) 대상이 아니다
        long early = koreaexim("XTS", "2099-01-01 15:00:00", "1500.0000", "1515.0000", false, "2099-01-02 00:00:00");
        jdbc.update("UPDATE exchange_rates SET send_rate = 1313.0000, fetched_at = ? WHERE id = ?", SAVED, early);
        saved(early);
        assertThat(state(alertId)).isEqualTo("ACTIVE:null");

        // 알림과 같은 시각에 저장된 행도 대상이 아니다(알림보다 늦어야 함)
        long same = koreaexim("XTS", "2099-01-02 15:00:00", "1300.0000", "1313.0000", false, CREATED);
        saved(same);
        assertThat(state(alertId)).isEqualTo("ACTIVE:null");
    }

    @Test
    void 같은_알림은_한_번만_도달하고_그_뒤_값으로_다시_판정하지_않는다() {
        long alertId = alert(users.get(0), "XTS", "SEND", "LTE", "1400");
        long first = koreaexim("XTS", AFTER, "1300.0000", "1313.0000", false);
        saved(first);
        String triggeredAt = jdbc.queryForObject("SELECT triggered_at FROM fx_alerts WHERE id = ?", String.class,
                alertId);

        saved(first);
        long second = koreaexim("XTS", "2099-01-02 15:00:00", "1200.0000", "1212.0000", false,
                "2099-01-03 02:00:00");
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
        long late = koreaexim("XTS", "2099-01-04 15:00:00", "1250.0000", "1262.5000", false, "2099-01-05 02:00:00");
        long notReached = koreaexim("XTS", AFTER, "1350.0000", "1363.5000", false);
        long reached = koreaexim("XTS", "2099-01-02 15:00:00", "1290.0000", "1302.9000", false, "2099-01-03 02:00:00");

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

    private long koreaexim(String currency, String rateAtUtc, String base, String send, boolean mock) {
        return koreaexim(currency, rateAtUtc, base, send, mock, SAVED);
    }

    /** 수출입은행 형식(송금 환율 있음). rate_date 는 rate_at(UTC) + 9시간의 날짜. savedAtUtc 는 처음 저장 시각(created_at). */
    private long koreaexim(String currency, String rateAtUtc, String base, String send, boolean mock,
                           String savedAtUtc) {
        return insert("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                        + "receive_rate, source, is_mock, fetched_at, created_at) VALUES (?, 1, DATE(? + INTERVAL 9 HOUR), "
                        + "?, ?, ?, ?, 'KOREAEXIM', ?, ?, ?)",
                currency, rateAtUtc, rateAtUtc, base, send, base, mock, savedAtUtc, savedAtUtc);
    }

    /** ECB 참고 환율 형식(송금 환율 없음). 처음 저장 시각은 SAVED. */
    private long marketRef(String currency, String rateAtUtc, String base, boolean mock) {
        return insert("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, provider, "
                        + "is_mock, fetched_at, created_at) VALUES (?, 1, DATE(?), ?, ?, ?, 'frankfurter-ecb', ?, ?, ?)",
                currency, rateAtUtc, rateAtUtc, base, mock ? "MOCK" : "MARKET_REF", mock, SAVED, SAVED);
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
