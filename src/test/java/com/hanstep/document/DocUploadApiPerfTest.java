package com.hanstep.document;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.hanstep.aijob.RabbitTestBroker;
import com.hanstep.aijob.messaging.AiJobRoute;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.dto.OcrFieldResult;
import com.hanstep.document.dto.OcrResultRequest;
import com.hanstep.document.service.DocumentInternalService;
import com.hanstep.document.storage.ArcImageStorage;
import com.hanstep.document.storage.ArcStorageProperties;
import com.hanstep.document.storage.R2ArcImageStorage;
import com.hanstep.perf.PerfSupport;
import com.hanstep.support.TestUsers;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 업로드 측정(#73 개선 후): 실제 POST /api/v1/documents(①, multipart front)를 기준선 2 시제품과 같은 조건으로 잰다.
 * 측정만 하고 assert 하지 않는다. 실제 등록증 이미지는 쓰지 않고 기준선 2 와 같은 합성 JPEG 를 쓰되,
 * 요청마다 COM 세그먼트 안 16바이트를 바꿔 sha256 이 달라지게 한다(같은 사진 멱등 반환을 재지 않음).
 *
 * <ul>
 *   <li>경로: 클라이언트 → Spring(RANDOM_PORT) → 검사 → 한 트랜잭션에서 documents 저장·R2 PUT·OCR 작업 등록 → 커밋 후
 *       OCR 작업 발행(confirm 대기까지 응답 시간에 포함). 시제품(PUT 만)보다 일이 많다</li>
 *   <li>R2: 실제 버킷(R2_ARC_BUCKET), 등록증 전용 키 R2_OCR_*. 객체는 arc/front/{UUID}.jpg 에 올라가므로
 *       이번 실행 사용자의 documents.object_key 로만 찾아 지운다(버킷 목록·접두사 삭제를 하지 않음)</li>
 *   <li>RabbitMQ: Testcontainers(RabbitTestBroker). 로컬 공용 hanstep-rabbitmq 는 쓰지 않는다</li>
 *   <li>Hikari: 단계마다 5ms 간격으로 활성 연결·연결 대기(pending) 최대값을 기록</li>
 *   <li>R2 호출 수: 실제 저장소를 감싼 계수기(PUT·DELETE)</li>
 * </ul>
 * 실행: .\gradlew.bat perfTest --tests "com.hanstep.document.DocUploadApiPerfTest"
 */
@Tag("perf")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DocUploadApiPerfTest.CountingStorageConfig.class)
class DocUploadApiPerfTest {

    static final String USER_PREFIX = "test-perf-doc-api-";
    static final List<String> REQUIRED_VARIABLES = List.of(
            "R2_ENDPOINT_URL", "R2_OCR_ACCESS_KEY_ID", "R2_OCR_SECRET_ACCESS_KEY", "R2_ARC_BUCKET");
    private static final Path REPORT = Path.of("build", "perf", "doc-upload-api.md");
    private static final Path RAW = Path.of("build", "perf", "doc-upload-api-raw.csv");
    private static final int[] SIZES = {1024 * 1024, 5 * 1024 * 1024};
    private static final int WARMUP = 2;
    private static final int RUNS = 20;
    private static final int CONCURRENCY = 10;
    private static final String BOUNDARY = "perfboundary9c1e";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** 기준선 2 시제품(multipart, 2026-11-16 16:19) 값. docs/perf/doc-upload-v1.md 표. {p50, p95} / {p50, max, heap MiB*10}. */
    private static final Map<Integer, long[]> PROTOTYPE_SEQ = Map.of(SIZES[0], new long[] {387, 885},
            SIZES[1], new long[] {647, 1208});
    private static final Map<Integer, long[]> PROTOTYPE_CONC = Map.of(SIZES[0], new long[] {670, 781, 605},
            SIZES[1], new long[] {1472, 1891, 1809});

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        RabbitTestBroker.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private CountingArcImageStorage storage;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private DocumentInternalService documentInternalService;

    @Autowired
    private Environment env;

    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    private final String userKey = USER_PREFIX + runId;
    private final List<String> csv = Collections.synchronizedList(new ArrayList<>());
    private String bearer;
    private long userId;

    private record Upload(long elapsedMs, int status, String error, Long documentId) {

        boolean ok() {
            return status == 202;
        }

        String failureKind() {
            return error.isEmpty() ? "HTTP " + status : error;
        }
    }

    private record Sample(long heapBaseline, long heapPeak, int activePeak, int pendingPeak) {
    }

    /** 실제 R2 저장소를 감싸 PUT·DELETE 를 센다. 테스트 클래스 안이라 다른 테스트 스캔에서 빠지고 이 테스트만 @Import. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CountingStorageConfig {

        @Bean
        @Primary
        CountingArcImageStorage countingArcImageStorage(ArcStorageProperties properties) {
            return new CountingArcImageStorage(new R2ArcImageStorage(properties));
        }
    }

    static class CountingArcImageStorage implements ArcImageStorage {

        final ArcImageStorage delegate;
        final AtomicInteger puts = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();

        CountingArcImageStorage(ArcImageStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(String objectKey, byte[] bytes, String contentType) {
            puts.incrementAndGet();
            delegate.put(objectKey, bytes, contentType);
        }

        @Override
        public void delete(String objectKey) {
            deletes.incrementAndGet();
            delegate.delete(objectKey);
        }
    }

    @AfterEach
    void tearDown() {
        if (missingVariables().isEmpty()) {
            int[] cleaned = cleanup(userKey);
            System.out.println("[DocUploadApiPerfTest] 끝 정리(tearDown): R2 객체 " + cleaned[0] + "건 지움, 사용자 "
                    + cleaned[1] + "명 삭제");
        }
    }

    @Test
    void 업로드_API_측정() throws Exception {
        List<String> missing = missingVariables();
        if (!missing.isEmpty()) {
            System.out.println("[DocUploadApiPerfTest] 측정 건너뜀: 비어 있는 변수 " + missing);
        }
        assumeTrue(missing.isEmpty(), "비어 있는 변수 " + missing + " 때문에 측정 건너뜀");

        // 이전 실행이 남긴 사용자·R2 객체(이 테스트 사용자 접두사의 documents.object_key 로만 찾음)
        int[] leftover = cleanup(USER_PREFIX + "%");
        System.out.println("[DocUploadApiPerfTest] 시작 전 정리: 잔여 R2 객체 " + leftover[0] + "건 지움, 잔여 사용자 "
                + leftover[1] + "명 삭제");
        userId = TestUsers.create(jdbc, userKey, "TEST PERF UPLOAD API USER");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(AiJobRoute.OCR.routingKey(), false);
        PerfSupport.write(RAW, "phase,size_bytes,seq,elapsed_ms,status,error,document_id\n");
        HikariPoolMXBean pool = ((HikariDataSource) dataSource.unwrap(HikariDataSource.class)).getHikariPoolMXBean();
        int poolSize = ((HikariDataSource) dataSource.unwrap(HikariDataSource.class)).getMaximumPoolSize();

        StringBuilder seqRows = new StringBuilder();
        StringBuilder concRows = new StringBuilder();
        Map<Integer, byte[]> bases = new TreeMap<>();
        for (int size : SIZES) {
            bases.put(size, DocUploadR2PerfTest.syntheticJpeg(size));
        }
        for (int size : SIZES) {
            byte[] base = bases.get(size);
            for (int i = 0; i < WARMUP; i++) {
                record("warmup", size, i, upload(body(base)));
            }
            flushCsv();

            int putsBefore = storage.puts.get();
            List<Upload> uploads = new ArrayList<>();
            Sample seqSample = sampled(() -> {
                for (int i = 0; i < RUNS; i++) {
                    byte[] body = body(base);   // 바이트 만들기는 시간 밖
                    Upload u = upload(body);
                    record("seq", size, i, u);
                    uploads.add(u);
                }
                return null;
            }, pool, false);
            flushCsv();
            List<Long> times = successTimes(uploads);
            int puts = storage.puts.get() - putsBefore;
            long[] proto = PROTOTYPE_SEQ.get(size);
            String p95 = percentileOrDash(times, 95);
            seqRows.append("| multipart(API) | ").append(mb(size)).append(" | ").append(RUNS)
                    .append(" | ").append(times.size()).append(" | ").append(RUNS - times.size())
                    .append(" | ").append(failureKinds(uploads))
                    .append(" | ").append(percentileOrDash(times, 50)).append(" | ").append(p95)
                    .append(" | ").append(proto[0]).append(" / ").append(proto[1])
                    .append(" | ").append(p95.equals("-") ? "-" : String.format("%+.0f%%",
                            (Long.parseLong(p95) - proto[1]) * 100.0 / proto[1]))
                    .append(" | 1 | ").append(String.format("%.2f", uploads.isEmpty() ? 0 : puts / (double) uploads.size()))
                    .append(" | ").append(seqSample.activePeak()).append(" / ").append(seqSample.pendingPeak())
                    .append(" |\n");

            // 동시: 본문 10개를 먼저 만들고(시작 전 힙에 포함) 같이 출발
            List<byte[]> bodies = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                bodies.add(body(base));
            }
            List<Upload> conc = new ArrayList<>();
            long[] wall = new long[1];
            Sample concSample = sampled(() -> {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Upload>> futures = new ArrayList<>();
                long started;
                try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY)) {
                    for (byte[] b : bodies) {
                        futures.add(executor.submit(() -> {
                            go.await();
                            return upload(b);
                        }));
                    }
                    started = System.nanoTime();
                    go.countDown();
                }
                wall[0] = (System.nanoTime() - started) / 1_000_000;
                int seq = 0;
                for (Future<Upload> f : futures) {
                    Upload u = f.get();
                    record("concurrent", size, seq++, u);
                    conc.add(u);
                }
                return null;
            }, pool, true);
            flushCsv();
            List<Long> concTimes = successTimes(conc);
            long[] cp = PROTOTYPE_CONC.get(size);
            concRows.append("| multipart(API) | ").append(mb(size)).append(" | ").append(CONCURRENCY)
                    .append(" | ").append(concTimes.size()).append(" | ").append(CONCURRENCY - concTimes.size())
                    .append(" | ").append(failureKinds(conc))
                    .append(" | ").append(percentileOrDash(concTimes, 50))
                    .append(" | ").append(concTimes.isEmpty() ? "-" : PerfSupport.max(concTimes))
                    .append(" | ").append(wall[0])
                    .append(" | ").append(mb(concSample.heapPeak() - concSample.heapBaseline()))
                    .append(" | ").append(cp[0]).append(" / ").append(cp[1]).append(" / ")
                    .append(String.format("%.1f MiB", cp[2] / 10.0))
                    .append(" | ").append(concSample.activePeak()).append(" / ").append(concSample.pendingPeak())
                    .append(" |\n");
            System.out.println("[DocUploadApiPerfTest] " + mb(size) + " seq 성공 " + times.size() + "/" + RUNS
                    + ", concurrent 성공 " + concTimes.size() + "/" + CONCURRENCY + ", Hikari 동시 최대 활성/대기 "
                    + concSample.activePeak() + "/" + concSample.pendingPeak());
        }

        String invalid = invalidInputs(bases.get(SIZES[0]));
        String consistency = consistency();
        String confirm = confirmOne();
        long ocrMessages = queueMessages(AiJobRoute.OCR.routingKey());
        Map<String, Long> jobs = new TreeMap<>();
        jdbc.query("SELECT CONCAT(status, '/', publish_status), COUNT(*) FROM ai_jobs WHERE user_id = ? GROUP BY 1",
                rs -> {
                    jobs.put(rs.getString(1), rs.getLong(2));
                }, userId);
        List<String> runKeys = objectKeys(userKey);
        int[] cleaned = cleanup(userKey);
        int left = remainingObjects(runKeys);
        System.out.println("[DocUploadApiPerfTest] 끝 정리: R2 객체 " + cleaned[0] + "건 지움, 그중 남은 객체(HEAD) " + left);

        writeReport(poolSize, seqRows, concRows, invalid, consistency, confirm, ocrMessages, jobs, leftover, cleaned,
                left);
    }

    // ---------- 요청 ----------

    /** 요청 1건. 예외를 밖으로 던지지 않고 실패로 기록한다(재시도 없음). */
    private Upload upload(byte[] multipartBody) {
        long started = System.nanoTime();
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/api/v1/documents"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", bearer)
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody)).build(), HttpResponse.BodyHandlers.ofString());
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            boolean success = r.statusCode() / 100 == 2;
            Long documentId = success ? MAPPER.readTree(r.body()).path("documentId").longValue() : null;
            // 202 가 아니면 상태 코드와 오류 code(있으면). 200 은 같은 사진 반환이라 이 측정에서는 실패로 센다
            String code = success ? "" : MAPPER.readTree(r.body()).path("code").asString("");
            String error = r.statusCode() == 202 ? "" : ("HTTP " + r.statusCode() + " " + code).trim();
            return new Upload(elapsed, r.statusCode(), error, documentId);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String message = Objects.toString(root.getMessage(), "");
            message = message.replaceAll("[,\\r\\n;|?]", " ").trim();
            return new Upload((System.nanoTime() - started) / 1_000_000, 0,
                    root.getClass().getSimpleName() + " " + message.substring(0, Math.min(80, message.length())), null);
        }
    }

    /** base 를 복사해 COM 세그먼트(오프셋 6~21) 16바이트를 바꾼 multipart 본문. JPEG 는 그대로 디코딩된다. */
    private static byte[] body(byte[] base) {
        byte[] jpeg = base.clone();
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        System.arraycopy(nonce, 0, jpeg, 6, nonce.length);
        return multipart("front", "front.jpg", "image/jpeg", jpeg);
    }

    private static byte[] multipart(String part, String filename, String contentType, byte[] content) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream(content.length + 512);
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + part + "\"; filename=\""
                    + filename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            body.write(content);
            body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            return body.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private interface Phase {
        Void run() throws Exception;
    }

    /** 단계를 돌리는 동안 5ms 마다 힙·Hikari 활성·대기 최대값을 본다. gc=true 면 시작 전 System.gc(). */
    private Sample sampled(Phase phase, HikariPoolMXBean pool, boolean gc) throws Exception {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        if (gc) {
            System.gc();
            Thread.sleep(200);
        }
        long baseline = memory.getHeapMemoryUsage().getUsed();
        AtomicLong heapPeak = new AtomicLong(baseline);
        AtomicInteger activePeak = new AtomicInteger();
        AtomicInteger pendingPeak = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (sampling.get()) {
                heapPeak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                activePeak.accumulateAndGet(pool.getActiveConnections(), Math::max);
                pendingPeak.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        try {
            phase.run();
        } finally {
            sampling.set(false);
            sampler.join();
        }
        return new Sample(baseline, heapPeak.get(), activePeak.get(), pendingPeak.get());
    }

    // ---------- 개선 후 확인 항목 ----------

    /** 잘못된 입력: 이미지 아님·10MB 초과·back 파트. 상태 코드, R2 PUT 증가, documents 증가. */
    private String invalidInputs(byte[] jpeg) {
        long docsBefore = documentCount();
        int putsBefore = storage.puts.get();
        byte[] notImage = new byte[64 * 1024];
        RANDOM.nextBytes(notImage);
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1024];
        System.arraycopy(jpeg, 0, tooLarge, 0, 4);
        byte[] front = multipart("front", "front.jpg", "image/jpeg", jpeg.clone());
        byte[] withBack = concatParts(front, multipart("back", "back.jpg", "image/jpeg", jpeg.clone()));
        Upload a = upload(multipart("front", "front.jpg", "image/jpeg", notImage));
        Upload b = upload(multipart("front", "front.jpg", "image/jpeg", tooLarge));
        Upload c = upload(withBack);
        record("invalid", notImage.length, 0, a);
        record("invalid", tooLarge.length, 1, b);
        record("invalid", jpeg.length, 2, c);
        flushCsv();
        return "| 이미지 아님(JPEG 라고 보냄) | " + a.status() + " " + a.error() + " |\n"
                + "| 10MB 초과(10 MiB + 1 KiB) | " + b.status() + " " + b.error() + " |\n"
                + "| back 파트 함께 | " + c.status() + " " + c.error() + " |\n"
                + "| 세 요청 뒤 R2 PUT 증가 | " + (storage.puts.get() - putsBefore) + " |\n"
                + "| 세 요청 뒤 documents 증가 | " + (documentCount() - docsBefore) + " |\n";
    }

    // 합친 multipart: 앞 본문의 끝 경계(--BOUNDARY--)를 떼고 뒤 본문을 붙인다
    private static byte[] concatParts(byte[] first, byte[] second) {
        byte[] closing = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[first.length - closing.length + 2 + second.length];
        System.arraycopy(first, 0, out, 0, first.length - closing.length);
        System.arraycopy("\r\n".getBytes(StandardCharsets.US_ASCII), 0, out, first.length - closing.length, 2);
        System.arraycopy(second, 0, out, first.length - closing.length + 2, second.length);
        return out;
    }

    /** documents.object_key 가 있는 행 수와 R2 에 실제로 있는 객체 수(HEAD). */
    private String consistency() {
        List<String> keys = objectKeys(userKey);
        int present = 0;
        try (S3Client s3 = s3()) {
            for (String key : keys) {
                if (exists(s3, key)) {
                    present++;
                }
            }
        }
        return "| documents.object_key 있는 행 | " + keys.size() + " |\n| 그중 R2 에 있는 객체(HEAD) | " + present + " |\n";
    }

    /** 1건 확정 흐름: OCR 결과(합성) → stayExpiry 입력 → 확정 → 원본 삭제 확인. */
    private String confirmOne() throws Exception {
        Long documentId = jdbc.queryForList("SELECT id FROM documents d JOIN users u ON u.id = d.user_id "
                + "WHERE u.external_user_key = ? AND d.object_key IS NOT NULL ORDER BY d.id LIMIT 1", Long.class,
                userKey).stream().findFirst().orElse(null);
        if (documentId == null) {
            return "| 확정 | 대상 문서 없음 |\n";
        }
        String key = jdbc.queryForObject("SELECT object_key FROM documents WHERE id = ?", String.class, documentId);
        documentInternalService.recordOcrResult(documentId, new OcrResultRequest(documentId, "perf-synthetic", 0,
                DocumentSide.FRONT, List.of(
                new OcrFieldResult("nameEn", "TEST PERF USER", new BigDecimal("0.99"), false),
                new OcrFieldResult("nationality", "PHL", new BigDecimal("0.99"), false),
                new OcrFieldResult("birthDate", "1995-08-15", new BigDecimal("0.99"), false),
                new OcrFieldResult("visaType", "E-9", new BigDecimal("0.99"), false),
                new OcrFieldResult("stayExpiry", null, null, false),
                new OcrFieldResult("registrationNo", "950815-5******", new BigDecimal("0.99"), true))));
        HttpResponse<String> patch = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/documents/" + documentId + "/fields"))
                .header("Authorization", bearer).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        "{\"fields\":[{\"fieldKey\":\"stayExpiry\",\"value\":\"2027-03-01\"}]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> confirm = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/documents/" + documentId + "/confirm"))
                .header("Authorization", bearer).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        boolean stillThere;
        try (S3Client s3 = s3()) {
            stillThere = exists(s3, key);
        }
        Map<String, Object> doc = jdbc.queryForMap("SELECT status, object_key FROM documents WHERE id = ?", documentId);
        Integer values = jdbc.queryForObject("SELECT COUNT(*) FROM document_fields WHERE document_id = ? "
                + "AND (extracted_value IS NOT NULL OR confirmed_value IS NOT NULL)", Integer.class, documentId);
        return "| PATCH /fields · POST /confirm | " + patch.statusCode() + " · " + confirm.statusCode() + " |\n"
                + "| 확정 뒤 documents.status · object_key | " + doc.get("status") + " · " + doc.get("object_key") + " |\n"
                + "| 확정 뒤 R2 원본 남음(HEAD) | " + stillThere + " |\n"
                + "| 확정 뒤 값이 남은 document_fields | " + values + " |\n";
    }

    // ---------- 정리 ----------

    /** 사용자 키(LIKE 패턴 가능)의 documents.object_key 객체를 지우고 사용자를 지운다(documents·ai_jobs CASCADE). */
    private int[] cleanup(String userKeyPattern) {
        int deleted = 0;
        for (String key : objectKeys(userKeyPattern)) {
            if (!key.startsWith("arc/")) {
                continue;   // 이 테스트가 만든 키 형식만 지운다
            }
            storage.delete(key);
            deleted++;
        }
        int users = jdbc.update("DELETE FROM users WHERE external_user_key LIKE ?", userKeyPattern);
        return new int[] {deleted, users};
    }

    private List<String> objectKeys(String userKeyPattern) {
        return jdbc.queryForList("SELECT d.object_key FROM documents d JOIN users u ON u.id = d.user_id "
                + "WHERE u.external_user_key LIKE ? AND d.object_key IS NOT NULL", String.class, userKeyPattern);
    }

    /** 정리 직전 키 목록을 다시 HEAD 해 남은 객체 수(0 이어야 함). */
    private int remainingObjects(List<String> keys) {
        int left = 0;
        try (S3Client s3 = s3()) {
            for (String key : keys) {
                if (exists(s3, key)) {
                    left++;
                }
            }
        }
        return left;
    }

    private S3Client s3() {
        return S3Client.builder().endpointOverride(URI.create(env.getProperty("R2_ENDPOINT_URL")))
                .region(Region.of(env.getProperty("R2_REGION", "auto")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        env.getProperty("R2_OCR_ACCESS_KEY_ID"), env.getProperty("R2_OCR_SECRET_ACCESS_KEY"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    private boolean exists(S3Client s3, String key) {
        try {
            s3.headObject(b -> b.bucket(env.getProperty("R2_ARC_BUCKET")).key(key));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private List<String> missingVariables() {
        return REQUIRED_VARIABLES.stream().filter(name -> {
            String v = env.getProperty(name);
            return v == null || v.isBlank();
        }).toList();
    }

    // ---------- 기록 ----------

    private void record(String phase, int size, int seq, Upload u) {
        csv.add(phase + "," + size + "," + seq + "," + u.elapsedMs() + "," + u.status() + "," + u.error() + ","
                + Objects.toString(u.documentId(), ""));
    }

    private void flushCsv() {
        List<String> lines;
        synchronized (csv) {
            lines = new ArrayList<>(csv);
            csv.clear();
        }
        if (lines.isEmpty()) {
            return;
        }
        try {
            Files.writeString(RAW, String.join("\n", lines) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long documentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE user_id = ?", Long.class, userId);
    }

    private long queueMessages(String queue) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queue);
        return info == null ? -1 : info.getMessageCount();
    }

    private static List<Long> successTimes(List<Upload> uploads) {
        return uploads.stream().filter(Upload::ok).map(Upload::elapsedMs).toList();
    }

    private static String failureKinds(List<Upload> uploads) {
        Map<String, Long> kinds = new TreeMap<>();
        uploads.stream().filter(u -> !u.ok()).forEach(u -> kinds.merge(u.failureKind(), 1L, Long::sum));
        return kinds.isEmpty() ? "-" : kinds.entrySet().stream().map(e -> e.getKey() + " ×" + e.getValue())
                .reduce((a, b) -> a + "<br>" + b).orElse("-");
    }

    private static String percentileOrDash(List<Long> values, int p) {
        if (values.isEmpty()) {
            return "-";
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return String.valueOf(sorted.get(Math.max(0, rank - 1)));
    }

    private static String mb(long bytes) {
        return String.format("%.1f MiB", bytes / 1024.0 / 1024.0);
    }

    private void writeReport(int poolSize, StringBuilder seqRows, StringBuilder concRows, String invalid,
                             String consistency, String confirm, long ocrMessages, Map<String, Long> jobs,
                             int[] leftover, int[] cleaned, int remaining) throws Exception {
        long maxHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        StringBuilder md = new StringBuilder(PerfSupport.header(jdbc, "업로드 API 측정(#73 ① multipart, 실제 엔드포인트)",
                "합성 JPEG(기준선 2 와 같음) " + List.of(mb(SIZES[0]), mb(SIZES[1])) + ", 요청마다 COM 16바이트를 바꿔 sha256 이 다름. "
                        + "R2 버킷 R2_ARC_BUCKET 의 arc/front/{UUID}.jpg",
                "크기마다 워밍업 " + WARMUP + "회 + 순차 " + RUNS + "회, 동시 " + CONCURRENCY + "건 1회",
                new String[] {"엔드포인트", "POST /api/v1/documents (multipart front)"},
                new String[] {"응답 시간에 포함", "검사 → documents 저장 → R2 PUT → OCR 작업 등록 → 커밋 → 커밋 후 OCR 발행(confirm 대기)"},
                new String[] {"클라이언트·Spring", "같은 JVM(RANDOM_PORT, java.net.http.HttpClient). 힙은 JVM 전체 값"},
                new String[] {"JVM 최대 힙", mb(maxHeap)},
                new String[] {"Hikari 최대 풀", String.valueOf(poolSize)},
                new String[] {"RabbitMQ", "Testcontainers(공용 hanstep-rabbitmq 미사용)"}));
        md.append("실패한 건은 재시도하지 않고 실패로 센다. 시간 통계는 성공(202)한 건만. 시제품 값은 기준선 2(2026-11-16 16:19, ")
                .append("docs/perf/doc-upload-v1.md)의 multipart 행. Hikari 는 단계 동안 5ms 표본의 최대값.\n\n");
        md.append("## 순차 업로드\n\n")
                .append("| 경로 | 크기 | 횟수 | 성공 | 실패 | 실패 종류 | p50(ms) | p95(ms) | 시제품 p50 / p95 | p95 변화 | "
                        + "클라이언트 왕복 | Spring→R2 PUT/건 | Hikari 활성 / 대기 최대 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n").append(seqRows);
        md.append("\n## 동시 ").append(CONCURRENCY).append("건\n\n")
                .append("| 경로 | 크기 | 건수 | 성공 | 실패 | 실패 종류 | 성공 건 p50(ms) | 성공 건 최대(ms) | 전체 시간(ms) | 힙 증가 | "
                        + "시제품 p50 / 최대 / 힙 증가 | Hikari 활성 / 대기 최대 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n").append(concRows);
        md.append("\n## 잘못된 입력\n\n| 항목 | 값 |\n|---|---|\n").append(invalid);
        md.append("\n## 정합성(R2 객체 수 = documents.object_key 수)\n\n| 항목 | 값 |\n|---|---|\n").append(consistency);
        md.append("\n## 확정 1건\n\n| 항목 | 값 |\n|---|---|\n").append(confirm);
        md.append("\n## OCR 작업\n\n| 항목 | 값 |\n|---|---|\n")
                .append("| 이번 실행 OCR 작업(status/publish_status 별) | ").append(jobs).append(" |\n")
                .append("| ocr.request 큐 메시지(Testcontainers) | ").append(ocrMessages).append(" |\n");
        md.append("\n## 정리\n\n| 항목 | 값 |\n|---|---|\n")
                .append("| 시작 전 잔여 R2 객체 / 사용자 | ").append(leftover[0]).append(" / ").append(leftover[1]).append(" |\n")
                .append("| 끝 정리 R2 객체 / 사용자 | ").append(cleaned[0]).append(" / ").append(cleaned[1]).append(" |\n")
                .append("| 정리 뒤 남은 객체(정리 직전 키를 다시 HEAD) | ").append(remaining).append(" |\n");
        md.append("\n원본: `").append(RAW).append("`\n");
        PerfSupport.write(REPORT, md.toString());
    }
}
