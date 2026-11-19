package com.hanstep.visa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.visa.service.VisaRoadmapTestSupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 템플릿 로드맵 LLM 재시도 간격(#139). 시간은 MutableClock 으로 옮긴다. */
class RoadmapLlmRetryCooldownTest {

    private static final Duration COOLDOWN = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T01:00:00Z"));
    private final RoadmapLlmRetryCooldown cooldown = new RoadmapLlmRetryCooldown(COOLDOWN, clock);

    @Test
    void 처음은_시도하고_쿨다운_안에서는_막고_지나면_다시_시도한다() {
        assertThat(cooldown.tryAcquire(1)).isTrue();
        assertThat(cooldown.tryAcquire(1)).isFalse();

        clock.advance(COOLDOWN.minusSeconds(1));
        assertThat(cooldown.tryAcquire(1)).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(cooldown.tryAcquire(1)).isTrue();
        assertThat(cooldown.tryAcquire(1)).isFalse();
        // 다른 로드맵은 따로 센다
        assertThat(cooldown.tryAcquire(2)).isTrue();
    }

    @Test
    void 새로_만든_템플릿_로드맵은_만든_때부터_쿨다운이다() {
        cooldown.record(1);

        assertThat(cooldown.tryAcquire(1)).isFalse();
        clock.advance(COOLDOWN);
        assertThat(cooldown.tryAcquire(1)).isTrue();
    }

    @Test
    void 지우면_기록이_없어져_바로_다시_시도할_수_있다() {
        cooldown.tryAcquire(1);

        cooldown.clear(1);

        assertThat(cooldown.hasAttempt(1)).isFalse();
        assertThat(cooldown.tryAcquire(1)).isTrue();
    }

    @Test
    void 쿨다운이_0이면_매번_시도한다() {
        RoadmapLlmRetryCooldown none = new RoadmapLlmRetryCooldown(Duration.ZERO, clock);

        assertThat(none.tryAcquire(1)).isTrue();
        assertThat(none.tryAcquire(1)).isTrue();
    }

    @Test
    void 기록이_넘치면_쿨다운이_지난_것부터_지운다() {
        for (long id = 1; id <= RoadmapLlmRetryCooldown.MAX_ENTRIES; id++) {
            cooldown.record(id);
        }
        assertThat(cooldown.size()).isEqualTo(RoadmapLlmRetryCooldown.MAX_ENTRIES);

        clock.advance(COOLDOWN);
        cooldown.record(10_001);

        // 지난 1,000개는 없는 것과 같아 지우고 방금 것만 남는다
        assertThat(cooldown.size()).isEqualTo(1);
        assertThat(cooldown.hasAttempt(10_001)).isTrue();
    }

    @Test
    void 쿨다운_안의_기록만으로_넘치면_모두_비운다() {
        for (long id = 1; id <= RoadmapLlmRetryCooldown.MAX_ENTRIES + 1; id++) {
            cooldown.record(id);
        }

        assertThat(cooldown.size()).isZero();
    }

    @Test
    void 같은_로드맵에_동시에_들어오면_하나만_시도한다() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            Callable<Boolean> call = () -> {
                start.await(5, TimeUnit.SECONDS);
                return cooldown.tryAcquire(7);
            };
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(call));
            }
            start.countDown();
            int acquired = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(5, TimeUnit.SECONDS)) {
                    acquired++;
                }
            }
            assertThat(acquired).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
