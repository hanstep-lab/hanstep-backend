package com.hanstep.chat.controller;

import com.hanstep.chat.support.FakeAgentServer;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.perf.PerfSupport;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * CHT-04 동시 연결 측정(측정만, 기본값은 바꾸지 않음). 기본 test 에서는 빠짐: ./gradlew perfTest
 *
 * <p>실제 포트로 띄운 Spring 에 CHT-04 를 동시에 N 개 보낸다. AI 자리에는 가짜 SSE 서버(답변 1개 5초, 100ms 마다 delta,
 * 요청마다 가상 스레드라 가짜 서버가 병목이 되지 않음)를 둔다. 실행기만 다른 두 컨텍스트(platform 16 / virtual)를 차례로 띄우고
 * 각각 워밍업 1회(N=50) 뒤 N=50·200 을 3회씩 잰다. 사용자는 perf- 사용자 200명(대화방 1개씩).
 * 기록: 첫 delta 시간(요청 시작 → 첫 chat.delta 도착) 중앙값·p95, 전체 완료 시간, 실패 수, 최대 스레드 수(ThreadMXBean, 플랫폼
 * 스레드만 셈 · 테스트 클라이언트·가짜 서버 포함), Hikari 커넥션 대기 최대 수·사용 중 최대 수, 가짜 AI 동시 연결 최대 수.
 */
@Tag("perf")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class ChatStreamConcurrencyPerfTest {

    static final int[] NS = {50, 200};
    static final int REPEATS = 3;
    static final int WARMUP_N = 50;
    static final int USERS = 200;
    static final int DELTAS = 50;
    static final int DELTA_INTERVAL_MS = 100;
    static final Path REPORT = Path.of("build", "perf", "chat-concurrency.md");
    static final Path RAW = Path.of("build", "perf", "chat-concurrency-raw.csv");

    static final FakeAgentServer AI = startAi();
    static final List<RunResult> RESULTS = new CopyOnWriteArrayList<>();
    static final List<String> RAW_ROWS = new CopyOnWriteArrayList<>();
    static volatile String header;

    record RunResult(String executor, int n, int rep, boolean warmup, List<Long> firstDeltaMs, long wallMs,
                     int failures, int peakThreads, int baseThreads, int maxHikariWaiting, int maxHikariActive,
                     int maxAiInFlight) {
    }

    @Nested
    @Order(1)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"hanstep.chat.stream-executor=platform", "hanstep.chat.stream-pool-size=16"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class Platform16 extends Scenario {

        @DynamicPropertySource
        static void ai(DynamicPropertyRegistry registry) {
            registry.add("hanstep.ai.base-url", AI::baseUrl);
        }

        @Test
        void 동시_연결_platform16() throws Exception {
            measure("platform(16)");
        }
    }

    @Nested
    @Order(2)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"hanstep.chat.stream-executor=virtual"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class Virtual extends Scenario {

        @DynamicPropertySource
        static void ai(DynamicPropertyRegistry registry) {
            registry.add("hanstep.ai.base-url", AI::baseUrl);
        }

        @Test
        void 동시_연결_virtual() throws Exception {
            measure("virtual");
        }
    }

    abstract static class Scenario {

        @LocalServerPort
        int port;

        @Autowired
        JwtTokenProvider jwtTokenProvider;

        @Autowired
        JdbcTemplate jdbc;

        @Autowired
        DataSource dataSource;

        void measure(String executor) throws Exception {
            AI.script(ChatStreamConcurrencyPerfTest::answer);
            PerfSupport.deletePerfUsers(jdbc);
            try {
                List<String[]> targets = new ArrayList<>(); // {bearer, sessionId}
                for (long[] user : PerfSupport.createUsers(jdbc, USERS)) {
                    jdbc.update("INSERT INTO chat_sessions (user_id) VALUES (?)", user[0]);
                    long sessionId = jdbc.queryForObject("SELECT id FROM chat_sessions WHERE user_id = ?", Long.class,
                            user[0]);
                    targets.add(new String[] {"Bearer " + jwtTokenProvider.issue(user[0]), String.valueOf(sessionId)});
                }
                if (header == null) {
                    header = PerfSupport.header(jdbc, "CHT-04 동시 연결 측정(실행기 platform vs virtual)",
                            "perf- 사용자 " + USERS + "명(대화방 1개씩), 가짜 AI(답변 1개 " + DELTAS * DELTA_INTERVAL_MS / 1000
                                    + "초, " + DELTA_INTERVAL_MS + "ms 마다 delta " + DELTAS + "개, 요청마다 가상 스레드)",
                            "실행기마다 워밍업 1회(N=" + WARMUP_N + ") + N=50·200 각 " + REPEATS + "회",
                            new String[] {"Spring", "실제 포트(Tomcat 기본 설정), Hikari 기본(최대 10)"},
                            new String[] {"외부 호출", "없음(가짜 AI 서버, 실제 AI·Anthropic 호출 없음)"});
                }
                HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
                run(executor, WARMUP_N, 0, true, targets, pool);
                for (int n : NS) {
                    for (int rep = 1; rep <= REPEATS; rep++) {
                        run(executor, n, rep, false, targets, pool);
                    }
                }
            } finally {
                PerfSupport.deletePerfUsers(jdbc);
            }
        }

        private void run(String executor, int n, int rep, boolean warmup, List<String[]> targets,
                         HikariPoolMXBean pool) throws Exception {
            AI.reset();
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            int baseThreads = threads.getThreadCount();
            threads.resetPeakThreadCount();
            AtomicBoolean sampling = new AtomicBoolean(true);
            AtomicInteger maxWaiting = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            Thread sampler = Thread.ofPlatform().daemon().start(() -> {
                while (sampling.get()) {
                    maxWaiting.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                    maxActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });

            List<long[]> perRequest; // {status, firstDeltaMs, totalMs, ok}
            long started = System.nanoTime();
            try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
                 HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                         .executor(clients).connectTimeout(Duration.ofSeconds(10)).build()) {
                List<Future<long[]>> futures = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String[] target = targets.get(i);
                    futures.add(clients.submit(() -> call(http, target[0], target[1])));
                }
                perRequest = new ArrayList<>();
                for (Future<long[]> f : futures) {
                    perRequest.add(f.get());
                }
            }
            long wallMs = (System.nanoTime() - started) / 1_000_000;
            sampling.set(false);
            sampler.join();

            List<Long> firstDelta = new ArrayList<>();
            int failures = 0;
            for (int i = 0; i < perRequest.size(); i++) {
                long[] r = perRequest.get(i);
                if (r[3] == 1) {
                    firstDelta.add(r[1]);
                } else {
                    failures++;
                }
                RAW_ROWS.add(String.format(Locale.ROOT, "%s,%d,%d,%b,%d,%d,%d,%d,%b", executor, n, rep, warmup, i + 1,
                        r[0], r[1], r[2], r[3] == 1));
            }
            RunResult result = new RunResult(executor, n, rep, warmup, firstDelta, wallMs, failures,
                    threads.getPeakThreadCount(), baseThreads, maxWaiting.get(), maxActive.get(), AI.maxInFlight());
            RESULTS.add(result);
            System.out.printf("[perf] chat %s N=%d rep=%d%s firstDelta median=%d p95=%d wall=%d ms failures=%d "
                            + "peakThreads=%d hikariWaitMax=%d aiInFlightMax=%d%n", executor, n, rep,
                    warmup ? "(warmup)" : "", median(firstDelta), p95(firstDelta), wallMs, failures,
                    result.peakThreads(), result.maxHikariWaiting(), result.maxAiInFlight());
        }

        /** {HTTP 상태, 첫 delta ms(없으면 -1), 완료 ms, chat.done 받았으면 1}. */
        private long[] call(HttpClient http, String bearer, String sessionId) {
            long started = System.nanoTime();
            long firstDelta = -1;
            boolean done = false;
            int status = 0;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/api/v1/chat/sessions/" + sessionId + "/messages/stream"))
                        .timeout(Duration.ofSeconds(180))
                        .header("Authorization", bearer)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"clientMessageId\":\"" + UUID.randomUUID()
                                + "\",\"content\":\"How much can I save?\"}"))
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                status = response.statusCode();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (firstDelta < 0 && line.startsWith("event:chat.delta")) {
                            firstDelta = (System.nanoTime() - started) / 1_000_000;
                        } else if (line.startsWith("event:chat.done")) {
                            done = true;
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                done = false;
            }
            return new long[] {status, firstDelta, (System.nanoTime() - started) / 1_000_000,
                    status == 200 && done ? 1 : 0};
        }
    }

    /** 가짜 AI 답변: 100ms 마다 delta 50개(5초) 뒤 done. */
    static void answer(FakeAgentServer.Received request, FakeAgentServer.Writer out) throws Exception {
        long started = System.nanoTime();
        for (int i = 0; i < DELTAS; i++) {
            out.event("agent.delta", "{\"text\":\"w" + i + " \"}");
            Thread.sleep(DELTA_INTERVAL_MS);
        }
        out.event("agent.done", "{\"modelId\":\"fake\",\"promptVersion\":\"perf\",\"timings\":{\"firstDeltaMs\":0,"
                + "\"toolMs\":0,\"totalMs\":" + (System.nanoTime() - started) / 1_000_000 + "}}");
    }

    @AfterAll
    static void report() throws IOException {
        AI.close();
        if (RESULTS.isEmpty()) {
            return;
        }
        StringBuilder md = new StringBuilder(header == null ? "# CHT-04 동시 연결 측정\n\n" : header);
        md.append("## 결과(측정 회차)\n\n")
                .append("첫 delta = 요청 시작 → 첫 chat.delta 도착(성공 요청만). 완료 = N 개를 동시에 보내고 모두 끝날 때까지. ")
                .append("최대 스레드는 JVM 플랫폼 스레드 최대치(가상 스레드는 세지 않음, 테스트 클라이언트·가짜 서버의 플랫폼 스레드 포함). ")
                .append("Hikari 는 5ms 마다 샘플링한 최대치. p95 는 nearest-rank.\n\n")
                .append("| 실행기 | N | 회차 | 첫 delta 중앙값 | 첫 delta p95 | 전체 완료 | 실패 | 최대 스레드(시작 시) | "
                        + "Hikari 대기 최대 | Hikari 사용 최대 | AI 동시 연결 최대 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (RunResult r : RESULTS) {
            if (!r.warmup()) {
                md.append(row(r.executor(), r.n(), String.valueOf(r.rep()), r.firstDeltaMs(), r.wallMs() + " ms",
                        r.failures(), r.peakThreads() + " (" + r.baseThreads() + ")", r.maxHikariWaiting(),
                        r.maxHikariActive(), r.maxAiInFlight()));
            }
        }
        md.append("\n## 3회 합산\n\n")
                .append("| 실행기 | N | 첫 delta 중앙값 | 첫 delta p95 | 전체 완료 중앙값(최소~최대) | 실패 합 | 최대 스레드 최대 | "
                        + "Hikari 대기 최대 |\n|---|---|---|---|---|---|---|---|\n");
        for (String executor : RESULTS.stream().map(RunResult::executor).distinct().toList()) {
            for (int n : NS) {
                List<RunResult> runs = RESULTS.stream()
                        .filter(r -> !r.warmup() && r.executor().equals(executor) && r.n() == n).toList();
                if (runs.isEmpty()) {
                    continue;
                }
                List<Long> pooled = runs.stream().flatMap(r -> r.firstDeltaMs().stream()).toList();
                List<Long> walls = runs.stream().map(RunResult::wallMs).toList();
                md.append(String.format(Locale.ROOT, "| %s | %d | %d ms | %d ms | %d ms (%d~%d) | %d | %d | %d |%n",
                        executor, n, median(pooled), p95(pooled), median(walls), Collections.min(walls),
                        Collections.max(walls), runs.stream().mapToInt(RunResult::failures).sum(),
                        runs.stream().mapToInt(RunResult::peakThreads).max().orElse(0),
                        runs.stream().mapToInt(RunResult::maxHikariWaiting).max().orElse(0)));
            }
        }
        md.append("\n워밍업 회차는 표에서 뺐고 ").append(RAW.getFileName()).append(" 에 warmup=true 로 남김.\n");
        PerfSupport.write(REPORT, md.toString());
        PerfSupport.write(RAW, "executor,n,rep,warmup,request,status,first_delta_ms,total_ms,ok\n"
                + String.join("\n", RAW_ROWS) + "\n");
    }

    private static String row(String executor, int n, String rep, List<Long> firstDelta, String wall, int failures,
                              String threads, int hikariWaiting, int hikariActive, int aiInFlight) {
        return String.format(Locale.ROOT, "| %s | %d | %s | %d ms | %d ms | %s | %d | %s | %d | %d | %d |%n",
                executor, n, rep, median(firstDelta), p95(firstDelta), wall, failures, threads, hikariWaiting,
                hikariActive, aiInFlight);
    }

    static long median(List<Long> values) {
        return values.isEmpty() ? -1 : PerfSupport.median(values);
    }

    // nearest-rank p95
    static long p95(List<Long> values) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    private static FakeAgentServer startAi() {
        try {
            return new FakeAgentServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
