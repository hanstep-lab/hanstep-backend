package com.hanstep.visa.service;

import static com.hanstep.visa.service.VisaRoadmapTestSupport.LLM_MODEL;
import static com.hanstep.visa.service.VisaRoadmapTestSupport.MAPPER;
import static com.hanstep.visa.service.VisaRoadmapTestSupport.insertUser;
import static com.hanstep.visa.service.VisaRoadmapTestSupport.llm;
import static com.hanstep.visa.service.VisaRoadmapTestSupport.roadmapFor;
import static com.hanstep.visa.service.VisaRoadmapTestSupport.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hanstep.visa.client.AiE74Client;
import com.hanstep.visa.client.AiRoadmapClient;
import com.hanstep.visa.service.VisaRoadmapTestSupport.MutableClock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 같은 템플릿 로드맵을 두 요청이 동시에 바꾸려는 경우(#139). 트랜잭션을 실제로 나눠야 해서 @Transactional 없이 커밋하고,
 * 넣은 사용자는 끝나고 지운다(프로필·평가·로드맵·단계는 CASCADE). 공용 규칙 행을 바꾸지 않도록 DRAFT 규칙도 허용한다.
 */
@SpringBootTest(properties = "hanstep.e74.include-draft-rules=true")
@Import(VisaRoadmapReplaceConcurrencyTest.ClockConfig.class)
class VisaRoadmapReplaceConcurrencyTest {

    private static final Duration COOLDOWN = Duration.ofMinutes(5);

    @TestConfiguration
    static class ClockConfig {

        @Bean
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-11-19T01:00:00Z"));
        }
    }

    @Autowired
    private VisaRoadmapService service;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiE74Client aiE74Client;

    @MockitoBean
    private AiRoadmapClient aiRoadmapClient;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private long userId;
    private ObjectNode roadmap;
    private long roadmapId;

    @BeforeEach
    void setUp() throws IOException {
        userId = insertUser(jdbc, "test-roadmap-race-" + UUID.randomUUID());
        ObjectNode assessment = sample("e74/calculate_response.json");
        roadmap = sample("visa/roadmap_response.json");
        when(aiE74Client.calculate(any())).thenAnswer(invocation -> assessment.deepCopy());
        when(aiRoadmapClient.create(any())).thenAnswer(invocation -> roadmapFor(roadmap, invocation.getArgument(0)));
        // 템플릿 문장 로드맵을 하나 저장해 두고 쿨다운을 지나게 한다
        roadmapId = service.create(userId, null, null).body().roadmapId();
        clock.advance(COOLDOWN);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void AI를_기다리는_사이_같은_로드맵_요청은_AI를_부르지_않고_저장된_것을_준다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return roadmapFor(llm(roadmap, "LLM 설명"), invocation.getArgument(0));
        }).when(aiRoadmapClient).create(any());

        CompletableFuture<VisaRoadmapService.Result> first =
                CompletableFuture.supplyAsync(() -> service.create(userId, null, null), pool);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        VisaRoadmapService.Result second = service.create(userId, null, null);
        release.countDown();
        VisaRoadmapService.Result replaced = first.get(10, TimeUnit.SECONDS);

        assertThat(second.created()).isFalse();
        assertThat(second.body().roadmap().path("text").path("source").stringValue()).isEqualTo("TEMPLATE");
        assertThat(replaced.created()).isTrue();
        assertThat(replaced.body().roadmapId()).isEqualTo(roadmapId);
        assertStoredOnce(replaced.body().roadmap());
        // 처음 저장 1번 + 다시 부른 1번
        verify(aiRoadmapClient, times(2)).create(any());
    }

    @Test
    void 두_요청이_모두_AI를_불러도_한_번만_바꾸고_다른_요청은_바뀐_것을_받는다() throws Exception {
        // 첫 요청이 AI 를 기다리는 동안 쿨다운이 지나 두 번째 요청도 AI 를 부른 상황(행 조건이 막는지 본다)
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            int n = calls.incrementAndGet();
            (n == 1 ? firstEntered : secondEntered).countDown();
            release.await(10, TimeUnit.SECONDS);
            return roadmapFor(llm(roadmap, "LLM 설명 " + n), invocation.getArgument(0));
        }).when(aiRoadmapClient).create(any());

        CompletableFuture<VisaRoadmapService.Result> a =
                CompletableFuture.supplyAsync(() -> service.create(userId, null, null), pool);
        assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
        clock.advance(COOLDOWN);
        CompletableFuture<VisaRoadmapService.Result> b =
                CompletableFuture.supplyAsync(() -> service.create(userId, null, null), pool);
        assertThat(secondEntered.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        List<VisaRoadmapService.Result> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));

        assertThat(results).extracting(r -> r.body().roadmapId()).containsOnly(roadmapId);
        assertThat(results).filteredOn(VisaRoadmapService.Result::created).hasSize(1);
        VisaRoadmapService.Result winner = results.stream().filter(VisaRoadmapService.Result::created).findFirst()
                .orElseThrow();
        VisaRoadmapService.Result loser = results.stream().filter(r -> !r.created()).findFirst().orElseThrow();
        assertThat(loser.body().reused()).isTrue();
        assertThat(loser.body().roadmap()).isEqualTo(winner.body().roadmap());
        assertStoredOnce(winner.body().roadmap());
        verify(aiRoadmapClient, times(3)).create(any());
    }

    // 행 1개, LLM 문장, 단계 4개이고 저장된 원문이 이긴 응답과 같다
    private void assertStoredOnce(JsonNode expected) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visa_roadmaps WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT model_id FROM visa_roadmaps WHERE id = ?", String.class, roadmapId))
                .isEqualTo(LLM_MODEL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visa_roadmap_steps WHERE roadmap_id = ?", Integer.class,
                roadmapId)).isEqualTo(4);
        assertThat(MAPPER.readTree(jdbc.queryForObject("SELECT result_json FROM visa_roadmaps WHERE id = ?",
                String.class, roadmapId))).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT description FROM visa_roadmap_steps WHERE roadmap_id = ? AND step_order = 1",
                String.class, roadmapId)).isEqualTo(expected.path("steps").get(0).path("description").stringValue());
    }
}
