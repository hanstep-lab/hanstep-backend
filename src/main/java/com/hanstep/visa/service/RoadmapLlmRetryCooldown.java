package com.hanstep.visa.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 템플릿 문장으로 저장된 로드맵의 LLM 재시도 간격(#139). roadmapId → 마지막으로 AI 로드맵을 부른 시각을 메모리에 둔다.
 * Spring 이 한 대라 메모리로 충분하다(재시작하면 기록이 사라져 재시도가 1회 늘 뿐). 여러 대로 늘리면 DB 칼럼으로 옮긴다.
 * 시각은 Clock 빈이 있으면 그것을(테스트), 없으면 시스템 시계를 쓴다.
 */
@Component
public class RoadmapLlmRetryCooldown {

    /** 이보다 많아지면 쿨다운이 지난 기록을 지운다. 그래도 넘치면 모두 비운다(재시도가 몇 번 늘 뿐). */
    static final int MAX_ENTRIES = 1_000;

    private final Map<Long, Instant> lastAttempts = new ConcurrentHashMap<>();
    private final Duration cooldown;
    private final Clock clock;

    @Autowired
    public RoadmapLlmRetryCooldown(@Value("${hanstep.roadmap.llm-retry-cooldown:5m}") Duration cooldown,
                                   ObjectProvider<Clock> clock) {
        this(cooldown, clock.getIfAvailable(Clock::systemUTC));
    }

    RoadmapLlmRetryCooldown(Duration cooldown, Clock clock) {
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /**
     * 쿨다운 밖이면 지금을 시도 시각으로 기록하고 true. 같은 roadmapId 로 동시에 들어오면 하나만 true 를 받는다
     * (다른 요청은 AI 를 부르지 않고 저장된 로드맵을 돌려준다).
     */
    public boolean tryAcquire(long roadmapId) {
        Instant now = clock.instant();
        boolean[] acquired = {false};
        lastAttempts.compute(roadmapId, (id, last) -> {
            if (last != null && now.isBefore(last.plus(cooldown))) {
                return last;
            }
            acquired[0] = true;
            return now;
        });
        if (acquired[0]) {
            prune(now);
        }
        return acquired[0];
    }

    /** 새로 만든 로드맵이 템플릿 문장이면 방금 AI 를 부른 것이므로 지금을 시도 시각으로 남긴다. */
    public void record(long roadmapId) {
        Instant now = clock.instant();
        lastAttempts.put(roadmapId, now);
        prune(now);
    }

    /** LLM 문장으로 바뀌면 더 재시도하지 않으므로 지운다. */
    public void clear(long roadmapId) {
        lastAttempts.remove(roadmapId);
    }

    boolean hasAttempt(long roadmapId) {
        return lastAttempts.containsKey(roadmapId);
    }

    int size() {
        return lastAttempts.size();
    }

    private void prune(Instant now) {
        if (lastAttempts.size() <= MAX_ENTRIES) {
            return;
        }
        // 쿨다운이 지난 기록은 없는 것과 같다
        lastAttempts.values().removeIf(last -> !now.isBefore(last.plus(cooldown)));
        if (lastAttempts.size() > MAX_ENTRIES) {
            lastAttempts.clear();
        }
    }
}
