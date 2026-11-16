package com.hanstep.aijob.messaging;

import com.hanstep.aijob.domain.AiJobResourceType;
import com.hanstep.aijob.domain.AiJobType;
import com.hanstep.aijob.service.AiJobEnqueueService;
import com.hanstep.aijob.service.AiJobRepublishScheduler;
import com.hanstep.aijob.service.AiJobRepublishService;
import com.hanstep.perf.PerfSupport;
import com.hanstep.support.TestUsers;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * 기준선 1(U2): 브로커가 멈췄을 때 커밋 후 발행이 어떻게 되는지 센다. 측정만 하고 assert 하지 않는다.
 *
 * <p>이 테스트 전용 RabbitMQ(Testcontainers)를 띄우고 {@code rabbitmqctl stop_app / start_app} 으로 멈췄다 켠다
 * (포트가 그대로라 Spring 연결 설정을 바꾸지 않아도 된다). 로컬 공용 컨테이너 hanstep-rabbitmq 는 쓰지 않는다.
 * 작업은 기존 등록 경로(호출한 쪽 트랜잭션 + AiJobEnqueueService.enqueue)로 만들고 실제로 커밋한다.
 * 데이터는 TestUsers 사용자와 이번 실행의 키 접두사로 만들고 끝나면 지운다.
 *
 * <p>단계: A OCR·브로커 멈춤 10건 → C POLICY_INGEST·브로커 멈춤 10건 → 브로커 다시 켬, 30초 동안 자동 발행 관찰
 * → R1 A·C 작업 재발행(AiJobRepublishService 직접 호출) → R2 같은 작업 다시 재발행(이미 PUBLISHED, 보내지 않아야 함)
 * → B OCR·브로커 켬 10건 → D POLICY_INGEST·브로커 켬 10건 → R3 D 작업 재발행(이미 PUBLISHED).
 *
 * <p>v2(#73 재측정) 도구 변경: R1·R2·R3 단계와 ocr.request 큐 메시지 수를 더했고, 결과를 -v2 파일에 쓴다(기준선 1 원본 보존).
 * perfTest 태스크는 주기 재발행 스케줄러를 끄므로(build.gradle) 관찰 30초 동안의 자동 발행은 스케줄러가 아니라
 * 다른 경로가 있는지를 본다. 재발행은 사용자 다시 시도와 같은 호출(republish(jobId, null))이다.
 * 실행: .\gradlew.bat perfTest --tests "com.hanstep.aijob.messaging.OcrJobBrokerDownPerfTest"
 */
@Tag("perf")
@SpringBootTest(properties = "spring.rabbitmq.connection-timeout=1s")
class OcrJobBrokerDownPerfTest {

    private static final Path REPORT = Path.of("build", "perf", "doc-u2-broker-down-v2.md");
    private static final Path RAW = Path.of("build", "perf", "doc-u2-broker-down-v2-raw.csv");
    private static final Path RAW_REPUBLISH = Path.of("build", "perf", "doc-u2-broker-down-v2-republish-raw.csv");
    private static final String OCR_QUEUE = AiJobRoute.OCR.routingKey();
    private static final String POLICY_QUEUE = AiJobRoute.POLICY_INGEST.routingKey();
    private static final int JOBS = 10;
    private static final int OBSERVE_SECONDS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4-management");

    static {
        BROKER.start();
    }

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", BROKER::getHost);
        registry.add("spring.rabbitmq.port", BROKER::getAmqpPort);
        registry.add("spring.rabbitmq.username", BROKER::getAdminUsername);
        registry.add("spring.rabbitmq.password", BROKER::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @Autowired
    private AiJobEnqueueService enqueueService;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AiJobRepublishService republishService;

    @Autowired
    private ApplicationContext context;

    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    private final String userKey = "test-perf-u2-" + runId;
    private final String keyPrefix = "perf-u2-" + runId + "-";

    private record Call(String phase, int seq, Long jobId, long elapsedMs, String exception, boolean documentKept,
                        boolean jobKept) {
    }

    /** 재발행 호출 1건. requested=보내기로 했는가, 뒤의 publish_status·status 는 호출이 끝난 뒤(커밋 후 발행까지) 값. */
    private record Republish(String phase, String jobType, long jobId, boolean requested, long elapsedMs,
                             String exception, String publishStatus, String status) {
    }

    /** 단계 끝의 큐 메시지 수. */
    private record Queues(String point, long ocr, long policy) {
    }

    @AfterEach
    void tearDown() throws Exception {
        broker("start_app");   // 중간에 실패해도 다음 실행을 위해 켜 둔다
        jdbc.update("DELETE FROM ai_jobs WHERE idempotency_key LIKE ?", keyPrefix + "%");
        jdbc.update("DELETE FROM users WHERE external_user_key = ?", userKey);   // documents 는 CASCADE
    }

    @Test
    void U2_브로커_멈춤_기준선() throws Exception {
        long userId = TestUsers.create(jdbc, userKey, "TEST PERF U2 USER");
        rabbitAdmin.initialize();   // 브로커가 켜져 있을 때 큐를 선언해 둔다
        List<Call> calls = new ArrayList<>();

        ExecResult stop = broker("stop_app");
        calls.addAll(run("A", JOBS, i -> ocr(userId, "A" + i)));
        calls.addAll(run("C", JOBS, i -> policy("C" + i)));
        Map<String, Long> beforeRestart = publishCounts();

        ExecResult start = broker("start_app");
        broker("await_startup");
        List<String> observe = new ArrayList<>();
        long policyQueueAtEnd = 0;
        for (int s = 1; s <= OBSERVE_SECONDS; s++) {
            Thread.sleep(1_000);
            if (s == 1 || s % 5 == 0) {
                policyQueueAtEnd = queueMessages(POLICY_QUEUE);
                observe.add("| " + s + " | " + publishCounts() + " | " + queueMessages(OCR_QUEUE) + " | "
                        + policyQueueAtEnd + " |");
            }
        }
        Map<String, Long> afterRestart = publishCounts();

        List<Queues> queues = new ArrayList<>();
        queues.add(new Queues("다시 켠 뒤 30초(R1 전)", queueMessages(OCR_QUEUE), queueMessages(POLICY_QUEUE)));
        List<Long> waiting = jobIds(calls, "A", "C");
        List<Republish> republishes = new ArrayList<>(republish("R1", waiting));
        Map<String, Long> afterRepublish = publishCounts();
        queues.add(new Queues("R1 뒤", queueMessages(OCR_QUEUE), queueMessages(POLICY_QUEUE)));
        republishes.addAll(republish("R2", waiting));
        queues.add(new Queues("R2 뒤", queueMessages(OCR_QUEUE), queueMessages(POLICY_QUEUE)));

        calls.addAll(run("B", JOBS, i -> ocr(userId, "B" + i)));
        calls.addAll(run("D", JOBS, i -> policy("D" + i)));
        queues.add(new Queues("B·D 뒤", queueMessages(OCR_QUEUE), queueMessages(POLICY_QUEUE)));
        republishes.addAll(republish("R3", jobIds(calls, "D")));
        queues.add(new Queues("R3 뒤", queueMessages(OCR_QUEUE), queueMessages(POLICY_QUEUE)));

        write(calls, stop, start, beforeRestart, afterRestart, observe, policyQueueAtEnd, republishes,
                afterRepublish, queues);
    }

    // ---------- 재발행(사용자 다시 시도와 같은 호출) ----------

    private static List<Long> jobIds(List<Call> calls, String... phases) {
        List<String> wanted = List.of(phases);
        return calls.stream().filter(c -> wanted.contains(c.phase()) && c.jobId() != null).map(Call::jobId).toList();
    }

    private List<Republish> republish(String phase, List<Long> jobIds) {
        List<Republish> results = new ArrayList<>();
        for (Long jobId : jobIds) {
            long started = System.nanoTime();
            boolean requested = false;
            String exception = "";
            try {
                requested = republishService.republish(jobId, null);
            } catch (RuntimeException e) {
                exception = e.getClass().getSimpleName();
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            Map<String, Object> row = jdbc.queryForMap("SELECT job_type, publish_status, status FROM ai_jobs WHERE id = ?",
                    jobId);
            results.add(new Republish(phase, (String) row.get("job_type"), jobId, requested, elapsed, exception,
                    (String) row.get("publish_status"), (String) row.get("status")));
        }
        return results;
    }

    // ---------- 작업 만들기(기존 등록 경로) ----------

    /** 등록증 업로드가 할 일과 같은 묶음: documents 1행 + OCR 작업 1건을 한 트랜잭션에서. {documentId, jobId} */
    private long[] ocr(long userId, String suffix) {
        return tx.execute(status -> {
            jdbc.update("INSERT INTO documents (user_id, document_type, side, object_key, content_type, "
                            + "file_size_bytes, sha256, retention_until) VALUES (?, 'ARC', 'FRONT', ?, 'image/jpeg', "
                            + "1048576, ?, NOW(3) + INTERVAL 1 DAY)",
                    userId, "perf/u2/" + runId + "/" + suffix + ".jpg", randomHex());
            long documentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            long jobId = enqueueService.enqueue(AiJobType.OCR, AiJobResourceType.DOCUMENT, documentId, userId,
                    keyPrefix + suffix).getId();
            return new long[] {documentId, jobId};
        });
    }

    /** 지금 발행 경로가 있는 유일한 작업(법령 등록과 같은 enqueue). 대상 행은 만들지 않는다(기존 테스트와 같음). */
    private long[] policy(String suffix) {
        return tx.execute(status -> new long[] {-1, enqueueService.enqueue(AiJobType.POLICY_INGEST,
                AiJobResourceType.POLICY_DOCUMENT, 900_000_000L + RANDOM.nextInt(99_999_999), null,
                keyPrefix + suffix).getId()});
    }

    private interface Creator {
        long[] create(int i);
    }

    private List<Call> run(String phase, int n, Creator creator) {
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long started = System.nanoTime();
            long[] ids = null;
            String exception = "";
            try {
                ids = creator.create(i);
            } catch (RuntimeException e) {
                exception = e.getClass().getSimpleName();
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            boolean documentKept = ids != null && ids[0] > 0 && exists("documents", ids[0]);
            boolean jobKept = ids != null && exists("ai_jobs", ids[1]);
            calls.add(new Call(phase, i, ids == null ? null : ids[1], elapsed, exception, documentKept, jobKept));
        }
        return calls;
    }

    // ---------- 관찰 ----------

    private boolean exists(String table, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id) > 0;
    }

    /** 이번 실행 작업의 (job_type, status, publish_status) 별 개수. */
    private Map<String, Long> publishCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT job_type, status, publish_status, COUNT(*) FROM ai_jobs WHERE idempotency_key LIKE ? "
                        + "GROUP BY job_type, status, publish_status ORDER BY job_type, status, publish_status",
                rs -> {
                    counts.put(rs.getString(1) + "/" + rs.getString(2) + "/" + rs.getString(3), rs.getLong(4));
                }, keyPrefix + "%");
        return counts;
    }

    private long queueMessages(String queue) {
        try {
            QueueInformation info = rabbitAdmin.getQueueInfo(queue);
            return info == null ? -1 : info.getMessageCount();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static ExecResult broker(String command) throws Exception {
        return BROKER.execInContainer("rabbitmqctl", command);
    }

    private static String randomHex() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // ---------- 리포트 ----------

    private void write(List<Call> calls, ExecResult stop, ExecResult start, Map<String, Long> beforeRestart,
                       Map<String, Long> afterRestart, List<String> observe, long policyQueueAtEnd,
                       List<Republish> republishes, Map<String, Long> afterRepublish, List<Queues> queues)
            throws Exception {
        StringBuilder republishCsv = new StringBuilder(
                "phase,job_type,job_id,requested,elapsed_ms,exception,publish_status,status\n");
        for (Republish r : republishes) {
            republishCsv.append(r.phase()).append(',').append(r.jobType()).append(',').append(r.jobId()).append(',')
                    .append(r.requested()).append(',').append(r.elapsedMs()).append(',').append(r.exception())
                    .append(',').append(r.publishStatus()).append(',').append(r.status()).append('\n');
        }
        PerfSupport.write(RAW_REPUBLISH, republishCsv.toString());
        boolean schedulerOff = context.getBeanNamesForType(AiJobRepublishScheduler.class).length == 0;
        StringBuilder csv = new StringBuilder("phase,seq,job_id,elapsed_ms,exception,document_kept,job_kept,"
                + "publish_status,status\n");
        for (Call c : calls) {
            Map<String, Object> row = c.jobId() == null ? Map.of()
                    : jdbc.queryForMap("SELECT publish_status, status FROM ai_jobs WHERE id = ?", c.jobId());
            csv.append(c.phase()).append(',').append(c.seq()).append(',').append(Objects.toString(c.jobId(), ""))
                    .append(',').append(c.elapsedMs()).append(',').append(c.exception()).append(',')
                    .append(c.documentKept()).append(',').append(c.jobKept()).append(',')
                    .append(Objects.toString(row.get("publish_status"), "")).append(',')
                    .append(Objects.toString(row.get("status"), "")).append('\n');
        }
        PerfSupport.write(RAW, csv.toString());

        StringBuilder md = new StringBuilder(PerfSupport.header(jdbc,
                "기준선 1(U2) 재측정 v2: 브로커 멈춤 시 커밋 후 발행과 재발행",
                "TestUsers 사용자 1명, 단계마다 작업 " + JOBS + "건(실제 커밋, 끝나고 삭제)",
                "단계 A·C·B·D 각 " + JOBS + "건, 재시작 후 " + OBSERVE_SECONDS + "초 관찰, 재발행 R1·R2·R3",
                new String[] {"브로커", "Testcontainers " + BROKER.getDockerImageName()
                        + " (rabbitmqctl stop_app/start_app, 공용 hanstep-rabbitmq 미사용)"},
                new String[] {"stop_app / start_app exit", stop.getExitCode() + " / " + start.getExitCode()},
                new String[] {"spring.rabbitmq.connection-timeout", "1s (이 테스트만)"},
                new String[] {"주기 재발행 스케줄러", schedulerOff ? "꺼짐(빈 없음)" : "**켜짐(측정 조건과 다름)**"},
                new String[] {"재발행 호출", "AiJobRepublishService.republish(jobId, null) 직접 호출(사용자 다시 시도와 같은 경로)"}));

        md.append("## 단계별 호출 결과\n\n")
                .append("| 단계 | 조건 | 건수 | 호출 쪽 예외 | documents 남음 | ai_jobs 남음 | 걸린 시간 중앙값(ms) | 최대(ms) |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        Map<String, String> conditions = Map.of("A", "OCR, 브로커 멈춤", "C", "POLICY_INGEST, 브로커 멈춤",
                "B", "OCR, 브로커 켬", "D", "POLICY_INGEST, 브로커 켬");
        for (String phase : List.of("A", "C", "B", "D")) {
            List<Call> ps = calls.stream().filter(c -> c.phase().equals(phase)).toList();
            List<Long> times = ps.stream().map(Call::elapsedMs).toList();
            md.append("| ").append(phase).append(" | ").append(conditions.get(phase)).append(" | ").append(ps.size())
                    .append(" | ").append(ps.stream().filter(c -> !c.exception().isEmpty()).count())
                    .append(" | ").append(phase.equals("A") || phase.equals("B")
                            ? String.valueOf(ps.stream().filter(Call::documentKept).count()) : "-")
                    .append(" | ").append(ps.stream().filter(Call::jobKept).count())
                    .append(" | ").append(PerfSupport.median(times)).append(" | ").append(PerfSupport.max(times))
                    .append(" |\n");
        }

        md.append("\n## (job_type/status/publish_status) 별 개수\n\n")
                .append("| 시점 | 개수 |\n|---|---|\n")
                .append("| 브로커 멈춤 중(A·C 뒤) | ").append(beforeRestart).append(" |\n")
                .append("| 다시 켠 뒤 ").append(OBSERVE_SECONDS).append("초(R1 전) | ").append(afterRestart).append(" |\n")
                .append("| R1 재발행 뒤 | ").append(afterRepublish).append(" |\n")
                .append("| 전부 끝난 뒤 | ").append(publishCounts()).append(" |\n");

        md.append("\n## 재발행\n\n")
                .append("| 단계 | 대상 | 호출 | 보내기로 함(true) | 호출 쪽 예외 | 호출 뒤 PUBLISHED | 걸린 시간 중앙값(ms) | 최대(ms) |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        Map<String, String> targets = Map.of("R1", "A·C(발행 대기)", "R2", "A·C(R1 뒤, 이미 PUBLISHED)",
                "R3", "D(이미 PUBLISHED)");
        for (String phase : List.of("R1", "R2", "R3")) {
            List<Republish> rs = republishes.stream().filter(r -> r.phase().equals(phase)).toList();
            List<Long> times = rs.stream().map(Republish::elapsedMs).toList();
            md.append("| ").append(phase).append(" | ").append(targets.get(phase)).append(" | ").append(rs.size())
                    .append(" | ").append(rs.stream().filter(Republish::requested).count())
                    .append(" | ").append(rs.stream().filter(r -> !r.exception().isEmpty()).count())
                    .append(" | ").append(rs.stream().filter(r -> "PUBLISHED".equals(r.publishStatus())).count())
                    .append(" | ").append(times.isEmpty() ? "-" : PerfSupport.median(times))
                    .append(" | ").append(times.isEmpty() ? "-" : PerfSupport.max(times)).append(" |\n");
        }
        md.append("\n| 시점 | ocr.request 큐 메시지 | policy.ingest 큐 메시지 |\n|---|---|---|\n");
        queues.forEach(q -> md.append("| ").append(q.point()).append(" | ").append(q.ocr()).append(" | ")
                .append(q.policy()).append(" |\n"));

        md.append("\n## 다시 켠 뒤 관찰(자동 발행 여부)\n\n")
                .append("| 경과(초) | 개수 | ocr.request 큐 메시지 | policy.ingest 큐 메시지 |\n|---|---|---|---|\n");
        observe.forEach(line -> md.append(line).append('\n'));
        long autoPublished = afterRestart.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/PUBLISHED")).mapToLong(Map.Entry::getValue).sum()
                - beforeRestart.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/PUBLISHED")).mapToLong(Map.Entry::getValue).sum();
        md.append("\n다시 켠 뒤 ").append(OBSERVE_SECONDS).append("초 동안 자동으로 PUBLISHED 가 된 작업: **")
                .append(autoPublished).append("건**, 그때 policy.ingest 큐 메시지: ").append(policyQueueAtEnd)
                .append("건\n");

        md.append("\n원본: `").append(RAW).append("`, `").append(RAW_REPUBLISH).append("`\n");
        PerfSupport.write(REPORT, md.toString());
    }
}
