package com.hanstep.fx.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.fx.repository.FxAlertRepository;
import com.hanstep.fx.repository.FxAlertRepository.JudgeRate;
import com.hanstep.fx.service.FxAlertTriggerService.Judgement;
import com.hanstep.fx.service.FxAlertTriggerService.Result;
import com.hanstep.support.TestUsers;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 알림을 여러 판정이 동시에 바꿔도 TRIGGERED 는 1번만(조건부 UPDATE ... WHERE status = 'ACTIVE').
 * 트랜잭션을 실제로 나눠야 해서 @Transactional 없이 커밋하고, 넣은 행은 끝나고 지운다. 환율은 시험용 코드 XTS.
 */
@SpringBootTest
class FxAlertTriggerConcurrencyTest {

    private static final int JUDGES = 10;

    @Autowired
    private FxAlertTriggerService service;

    @Autowired
    private FxAlertRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        deleteRates();
        userId = TestUsers.create(jdbc, "test-fx-trigger-concurrency-" + UUID.randomUUID(), "TEST FX TRIGGER RACE");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE id = ?", userId); // fx_alerts 도 같이 지워진다
        deleteRates();
    }

    @Test
    void 같은_알림을_10개_판정이_동시에_봐도_TRIGGERED는_1번이고_도달_행과_시각이_하나다() throws Exception {
        jdbc.update("INSERT INTO fx_alerts (user_id, currency, rate_type, comparator, target_rate, created_at) "
                + "VALUES (?, 'XTS', 'SEND', 'LTE', 1400.0000, NOW(3) - INTERVAL 1 MINUTE)", userId);
        long alertId = jdbc.queryForObject("SELECT id FROM fx_alerts WHERE user_id = ?", Long.class, userId);
        // 판정마다 다른 행(값은 모두 목표 아래)을 쓴다. 누가 이겨도 triggered_rate_id 는 하나여야 한다
        List<Long> rateIds = new ArrayList<>();
        for (int i = 0; i < JUDGES; i++) {
            jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                    + "receive_rate, source, is_mock, fetched_at) VALUES ('XTS', 1, '2099-01-03', "
                    + "'2099-01-02 15:00:00' + INTERVAL ? SECOND, 1300.0000, 1313.0000, 1287.0000, 'KOREAEXIM', FALSE, "
                    + "NOW(3))", i);
            rateIds.add(jdbc.queryForObject("SELECT MAX(id) FROM exchange_rates WHERE currency = 'XTS'", Long.class));
        }
        List<JudgeRate> rates = repository.realRates(rateIds);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(JUDGES);
        List<Result> results = new ArrayList<>();
        try {
            List<Future<List<Judgement>>> futures = new ArrayList<>();
            for (int i = 0; i < JUDGES; i++) {
                JudgeRate rate = rates.get(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.judge("XTS", List.of(rate), true, Instant.now().truncatedTo(ChronoUnit.MILLIS));
                }));
            }
            start.countDown();
            for (Future<List<Judgement>> future : futures) {
                future.get(30, TimeUnit.SECONDS).forEach(j -> results.add(j.result()));
            }
        } finally {
            pool.shutdownNow();
        }

        // ACTIVE 를 읽은 판정은 UPDATE 0행이면 ALREADY_CHANGED, 이미 바뀐 뒤 읽은 판정은 대상이 없다
        assertThat(results).containsOnlyOnce(Result.TRIGGERED).containsOnly(Result.TRIGGERED, Result.ALREADY_CHANGED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fx_alerts WHERE user_id = ? AND status = 'TRIGGERED' "
                + "AND triggered_at IS NOT NULL", Integer.class, userId)).isOne();
        assertThat(rateIds).contains(jdbc.queryForObject("SELECT triggered_rate_id FROM fx_alerts WHERE id = ?",
                Long.class, alertId));
    }

    private void deleteRates() {
        jdbc.update("DELETE FROM exchange_rates WHERE currency = 'XTS' AND rate_date = '2099-01-03'");
    }
}
