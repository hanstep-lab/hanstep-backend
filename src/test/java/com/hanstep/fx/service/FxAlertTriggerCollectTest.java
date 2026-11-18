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
    void 수집으로_새_값이_저장되면_그_통화의_ACTIVE_알림을_판정한다() {
        long reyes = TestUsers.create(jdbc, "test-fx-trigger-collect-0", "TEST FX COLLECT 0");
        long other = TestUsers.create(jdbc, "test-fx-trigger-collect-1", "TEST FX COLLECT 1");
        long usdSend = alert(reyes, "USD", "SEND", "LTE", "1306.72", "2031-01-05 00:00:00");
        // 고시일 00:00(KST)보다 늦게 만든 알림은 그날 고시값으로 판정하지 않는다
        long usdLate = alert(other, "USD", "SEND", "LTE", "1400", "2031-01-05 16:00:00");
        long phpBase = alert(reyes, "PHP", "BASE", "GTE", "20.003", "2031-01-01 00:00:00");
        long phpSend = alert(other, "PHP", "SEND", "GTE", "1", "2031-01-01 00:00:00");

        collectService.collectLatest(MONDAY);

        long usdRate = rateId("USD", "KOREAEXIM", "2031-01-06");
        long phpRate = rateId("PHP", "MARKET_REF", "2031-01-03");
        assertThat(state(usdSend)).isEqualTo("TRIGGERED:" + usdRate + ":1306.7200");
        assertThat(state(usdLate)).isEqualTo("ACTIVE:null:null");
        assertThat(state(phpBase)).isEqualTo("TRIGGERED:" + phpRate + ":20.0030");
        assertThat(state(phpSend)).isEqualTo("ACTIVE:null:null");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fx_alerts WHERE id IN (?, ?) AND triggered_at IS NOT NULL",
                Integer.class, usdSend, phpBase)).isEqualTo(2);
    }

    @Test
    void 같은_값을_다시_받으면_이벤트가_없어_다시_판정하지_않는다() {
        long userId = TestUsers.create(jdbc, "test-fx-trigger-collect-2", "TEST FX COLLECT 2");
        collectService.collectLatest(MONDAY);
        // 첫 수집 뒤에 넣은 알림(만든 시각은 고시 전으로 둬서 값만 보면 닿는다). 같은 값 재수집은 "저장됨"이 아니라 판정하지 않는다
        long alertId = alert(userId, "USD", "SEND", "LTE", "2000", "2031-01-05 00:00:00");

        collectService.collectLatest(MONDAY);

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

    private long rateId(String currency, String source, String rateDate) {
        return jdbc.queryForObject("SELECT id FROM exchange_rates WHERE currency = ? AND source = ? AND rate_date = ?",
                Long.class, currency, source, rateDate);
    }

    private long alert(long owner, String currency, String rateType, String comparator, String target,
                       String createdAtUtc) {
        jdbc.update("INSERT INTO fx_alerts (user_id, currency, rate_type, comparator, target_rate, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", owner, currency, rateType, comparator, target, createdAtUtc);
        return jdbc.queryForObject("SELECT MAX(id) FROM fx_alerts WHERE user_id = ?", Long.class, owner);
    }
}
