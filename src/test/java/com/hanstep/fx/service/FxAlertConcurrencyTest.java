package com.hanstep.fx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.fx.domain.FxComparator;
import com.hanstep.fx.domain.FxRateType;
import com.hanstep.fx.dto.FxAlertCreateRequest;
import com.hanstep.fx.dto.FxAlertResponse;
import com.hanstep.support.TestUsers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 사용자·통화·방향의 알림을 동시에 만들 때 1건만 들어간다(uq_fx_alerts_active).
 * 트랜잭션을 실제로 나눠야 해서 @Transactional 없이 커밋하고, 넣은 행은 끝나고 지운다
 * (사용자를 지우면 fx_alerts 가 같이 지워진다). 환율은 ISO 4217 시험용 코드 XTS 로 넣고 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FxAlertConcurrencyTest {

    private static final String RATE_AT = "2026-11-05 15:00:00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private FxAlertService service;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        deleteRate();
        userId = TestUsers.create(jdbc, "test-fx-alert-concurrency-" + UUID.randomUUID(), "TEST FX CONCURRENCY");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        jdbc.update("""
                INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate,
                  receive_rate, source, is_mock, fetched_at)
                VALUES ('XTS', 1, '2026-11-06', ?, 1400.0000, 1414.0000, 1386.0000, 'MOCK', TRUE, ?)""",
                RATE_AT, RATE_AT);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        deleteRate();
    }

    @Test
    void 같은_조합을_10개_동시에_보내도_201은_1건이고_나머지는_409() throws Exception {
        int requests = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String body = "{\"currency\":\"XTS\",\"comparator\":\"LTE\",\"targetRate\":" + (1350 + i) + "}";
                Callable<Integer> call = () -> {
                    start.await();
                    return mockMvc.perform(post("/api/v1/fx/alerts").header(HttpHeaders.AUTHORIZATION, bearer)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(requests - 1);
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    void 먼저_만든_알림이_커밋되기_전에_온_요청은_기다렸다가_409() throws Exception {
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 첫 요청: 넣고 커밋하지 않은 채 기다린다
            Future<FxAlertResponse> first = pool.submit(() -> transactionTemplate.execute(status -> {
                FxAlertResponse created = service.create(userId, request("1350"));
                firstInserted.countDown();
                await(releaseFirst);
                return created;
            }));
            assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();

            // 둘째 요청: 첫 행이 커밋되기 전에 시작한다. 미리 조회해 막는 방식이면 ACTIVE 가 안 보여 둘 다 들어가는 상황
            Future<BusinessException> second = pool.submit(() -> {
                try {
                    service.create(userId, request("1340"));
                    return null;
                } catch (BusinessException e) {
                    return e;
                }
            });
            Thread.sleep(300);
            assertThat(second.isDone()).as("UNIQUE 검사가 첫 행의 커밋을 기다린다").isFalse();
            releaseFirst.countDown();

            Long firstId = first.get(10, TimeUnit.SECONDS).alertId();
            BusinessException error = second.get(10, TimeUnit.SECONDS);
            assertThat(error).isNotNull();
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_REQUEST);
            assertThat(error.getDetails()).containsEntry("alertId", firstId);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
        assertThat(activeCount()).isEqualTo(1);
    }

    private static FxAlertCreateRequest request(String targetRate) {
        return new FxAlertCreateRequest("XTS", FxRateType.SEND, FxComparator.LTE, new BigDecimal(targetRate));
    }

    private int activeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fx_alerts WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, userId);
    }

    private void deleteRate() {
        jdbc.update("DELETE FROM exchange_rates WHERE currency = 'XTS' AND source = 'MOCK' AND rate_at = ?", RATE_AT);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
