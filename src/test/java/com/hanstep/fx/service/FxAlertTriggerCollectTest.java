package com.hanstep.fx.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.fx.FakeFxSourceServer;
import com.hanstep.support.TestUsers;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집기 저장 → "저장됨" 이벤트 → 도달 판정까지 한 번에(실제 DB, 롤백). 원천은 FakeFxSourceServer(2031년 값).
 * 행의 처음 저장 시각(created_at)은 DB 의 지금 시각이라 알림은 그보다 1분 먼저 만든 것으로 넣는다.
 * 2031-01-06(월) 수집: USD 는 수출입은행 고시(rate_at 2031-01-06 00:00 KST = 01-05 15:00 UTC, 송금 보낼 때 1306.72),
 * PHP 는 ECB 최신 기준일 2031-01-03 값(rate_at 01-03 15:00 UTC, 20.003, 송금 환율 없음).
 */
@SpringBootTest
@Transactional
class FxAlertTriggerCollectTest {

    private static final LocalDate MONDAY = LocalDate.of(2031, 1, 6);

    @Autowired
    private FxCollectService collectService;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        FakeFxSourceServer.register(registry);
    }

    @BeforeEach
    void setUp() {
        FakeFxSourceServer.reset();
    }

    @Test
    void 수집으로_알림_뒤에_처음_저장된_값이면_그_통화의_ACTIVE_알림을_판정한다() {
        long reyes = TestUsers.create(jdbc, "test-fx-trigger-collect-0", "TEST FX COLLECT 0");
        long other = TestUsers.create(jdbc, "test-fx-trigger-collect-1", "TEST FX COLLECT 1");
        long usdSend = alert(reyes, "USD", "SEND", "LTE", "1306.72");
        long usdNotReached = alert(other, "USD", "SEND", "LTE", "1306.71");
        long phpBase = alert(reyes, "PHP", "BASE", "GTE", "20.003");
        long phpSend = alert(other, "PHP", "SEND", "GTE", "1");

        collectService.collectLatest(MONDAY);

        long usdRate = rateId("USD", "KOREAEXIM", "2031-01-06");
        long phpRate = rateId("PHP", "MARKET_REF", "2031-01-03");
        assertThat(state(usdSend)).isEqualTo("TRIGGERED:" + usdRate + ":1306.7200");
        assertThat(state(usdNotReached)).isEqualTo("ACTIVE:null:null");
        assertThat(state(phpBase)).isEqualTo("TRIGGERED:" + phpRate + ":20.0030");
        assertThat(state(phpSend)).isEqualTo("ACTIVE:null:null");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fx_alerts WHERE id IN (?, ?) AND triggered_at IS NOT NULL",
                Integer.class, usdSend, phpBase)).isEqualTo(2);
    }

    @Test
    void 이미_있던_행은_다시_수집해_fetched_at만_갱신돼도_판정하지_않는다() throws Exception {
        long userId = TestUsers.create(jdbc, "test-fx-trigger-collect-2", "TEST FX COLLECT 2");
        collectService.collectLatest(MONDAY);
        // 첫 수집 뒤에 만든 알림. 값은 닿지만 행이 알림보다 먼저 저장됐고, 같은 값 재수집은 "저장됨"이 아니다
        jdbc.update("INSERT INTO fx_alerts (user_id, currency, rate_type, comparator, target_rate, created_at) "
                + "VALUES (?, 'USD', 'SEND', 'LTE', 2000, NOW(3))", userId);
        long alertId = jdbc.queryForObject("SELECT id FROM fx_alerts WHERE user_id = ?", Long.class, userId);
        String fetchedAt = usdFetchedAt();
        Thread.sleep(5);

        collectService.collectLatest(MONDAY);

        // 1시간 수집은 같은 값이면 fetched_at(마지막 확인 시각)만 갱신한다(#124). 그래도 판정 대상이 아니다
        assertThat(usdFetchedAt()).isGreaterThan(fetchedAt);
        assertThat(state(alertId)).isEqualTo("ACTIVE:null:null");
    }

    /** "상태:triggered_rate_id:도달 행의 rate_type 값". */
    private String state(long alertId) {
        return jdbc.queryForObject("""
                SELECT CONCAT(a.status, ':', IFNULL(a.triggered_rate_id, 'null'), ':',
                       IFNULL(CASE a.rate_type WHEN 'SEND' THEN t.send_rate ELSE t.base_rate END, 'null'))
                FROM fx_alerts a LEFT JOIN exchange_rates t ON t.id = a.triggered_rate_id WHERE a.id = ?""",
                String.class, alertId);
    }

    private String usdFetchedAt() {
        return jdbc.queryForObject("SELECT fetched_at FROM exchange_rates WHERE currency = 'USD' AND source = 'KOREAEXIM' "
                + "AND rate_date = '2031-01-06'", String.class);
    }

    private long rateId(String currency, String source, String rateDate) {
        return jdbc.queryForObject("SELECT id FROM exchange_rates WHERE currency = ? AND source = ? AND rate_date = ?",
                Long.class, currency, source, rateDate);
    }

    /** 수집 1분 전에 만든 알림. */
    private long alert(long owner, String currency, String rateType, String comparator, String target) {
        jdbc.update("INSERT INTO fx_alerts (user_id, currency, rate_type, comparator, target_rate, created_at) "
                + "VALUES (?, ?, ?, ?, ?, NOW(3) - INTERVAL 1 MINUTE)", owner, currency, rateType, comparator, target);
        return jdbc.queryForObject("SELECT MAX(id) FROM fx_alerts WHERE user_id = ?", Long.class, owner);
    }
}
