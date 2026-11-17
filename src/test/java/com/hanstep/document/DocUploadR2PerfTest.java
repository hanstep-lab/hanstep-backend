package com.hanstep.document;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.perf.PerfSupport;
import com.hanstep.support.TestUsers;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기준선 2: 등록증 업로드 경로 두 가지를 R2(S3 호환)로 비교한다. 측정만 하고 assert 하지 않는다.
 * 실제 등록증 이미지는 쓰지 않고 "SAMPLE" 글자를 그린 합성 JPEG(1MB, 5MB)를 만든다.
 * <ol>
 *   <li>multipart: 클라이언트 → Spring(RANDOM_PORT) → 형식·크기·sha256 검사 → R2 PutObject</li>
 *   <li>presigned: Spring 이 PUT URL 발급 → 클라이언트가 R2 에 직접 PUT → 완료 알림 → Spring 이 HEAD 로 크기 확인</li>
 * </ol>
 * 시제품 컨트롤러({@link PrototypeUploadController})는 test 소스에만 있고 이 테스트만 @Import 한다.
 * R2 접속 정보는 Spring 이 .env 를 읽는 방식(spring.config.import) 그대로 R2_ENDPOINT_URL·R2_REGION·
 * 등록증 버킷 전용 키 R2_OCR_ACCESS_KEY_ID·R2_OCR_SECRET_ACCESS_KEY·R2_ARC_BUCKET 를 쓴다(정책·상품 키 R2_ACCESS_KEY_ID 는
 * 쓰지 않음). 하나라도 비면 비어 있는 변수 이름을 출력하고 건너뛴다. 객체는 perf/ 아래에만 올린다. 시작 전에 이전 실행이 남긴
 * perf/ 객체를 세고 지우며, 끝나면 perf/ 를 모두 지우고 남은 개수 0 을 확인한다(perf/ 밖은 건드리지 않음).
 * 업로드 한 건이 실패해도 예외로 끝내지 않고 실패 건수·종류를 기록한다(재시도 없음). raw.csv 는 단계마다 덧붙여 쓴다.
 * 실행: .\gradlew.bat perfTest --tests "com.hanstep.document.DocUploadR2PerfTest"
 */
@Tag("perf")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.servlet.multipart.max-file-size=10MB", "spring.servlet.multipart.max-request-size=11MB"})
@Import(DocUploadR2PerfTest.PrototypeUploadController.class)
class DocUploadR2PerfTest {

    static final String PREFIX = "perf/";
    static final List<String> REQUIRED_VARIABLES = List.of(
            "R2_ENDPOINT_URL", "R2_OCR_ACCESS_KEY_ID", "R2_OCR_SECRET_ACCESS_KEY", "R2_ARC_BUCKET");
    private static final Path REPORT = Path.of("build", "perf", "doc-upload-r2.md");
    private static final Path RAW = Path.of("build", "perf", "doc-upload-r2-raw.csv");
    private static final int[] SIZES = {1024 * 1024, 5 * 1024 * 1024};
    private static final int WARMUP = 2;
    private static final int RUNS = 20;
    private static final int CONCURRENCY = 10;
    private static final int ORPHANS = 5;
    private static final String BOUNDARY = "perfboundary7f3a";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PrototypeUploadController controller;

    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    private final String userKey = "test-perf-upload-" + runId;
    /** 아직 raw.csv 에 쓰지 않은 줄. 단계가 끝날 때마다 flushCsv() 로 파일에 덧붙이고 비운다(중간에 죽어도 끝난 단계는 남게). */
    private final List<String> csv = Collections.synchronizedList(new ArrayList<>());
    private String bearer;

    private record Payload(int size, byte[] jpeg, String sha256, byte[] multipartBody) {
    }

    /** status 200 만 성공. 예외로 끝난 건은 status 0 이고 error 에 예외 종류를 남긴다(재시도하지 않음). */
    private record Upload(long elapsedMs, int clientRoundTrips, long r2Calls, int status, String key, String error) {

        boolean ok() {
            return status == 200;
        }

        String failureKind() {
            return error.isEmpty() ? "HTTP " + status : error;
        }
    }

    private record Concurrent(long wallMs, long heapBaseline, long heapPeak, List<Upload> uploads) {
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE external_user_key = ?", userKey);
        if (controller.configured()) {
            int deleted = controller.deleteAllUnderPrefix();
            int left = controller.listKeys(PREFIX).size();
            System.out.println("[DocUploadR2PerfTest] 끝 정리(tearDown): perf/ 객체 " + deleted + "건 지움, 남은 " + left + "건");
        }
    }

    @Test
    void 업로드_경로_기준선() throws Exception {
        List<String> missing = controller.missingVariables();
        if (!missing.isEmpty()) {
            System.out.println("[DocUploadR2PerfTest] 측정 건너뜀: 비어 있는 변수 " + missing);
        }
        assumeTrue(missing.isEmpty(), "비어 있는 변수 " + missing + " 때문에 측정 건너뜀");
        bearer = "Bearer " + jwtTokenProvider.issue(TestUsers.create(jdbc, userKey, "TEST PERF UPLOAD USER"));

        // 이전 실행이 중간에 죽어 남긴 perf/ 객체(예: 15:35 실패 잔여물)를 먼저 지운다. perf/ 밖은 건드리지 않는다
        int leftover = controller.listKeys(PREFIX).size();
        int leftoverDeleted = controller.deleteAllUnderPrefix();
        int leftoverAfter = controller.listKeys(PREFIX).size();
        System.out.println("[DocUploadR2PerfTest] 시작 전 정리: perf/ 잔여 객체 " + leftover + "건, " + leftoverDeleted
                + "건 지움, 남은 " + leftoverAfter + "건");
        if (leftoverAfter != 0) {
            throw new IllegalStateException("시작 전 perf/ 정리 후에도 " + leftoverAfter + "건 남음");
        }

        PerfSupport.write(RAW, "method,size_bytes,phase,seq,elapsed_ms,client_round_trips,r2_calls,status,error\n");

        List<Payload> payloads = new ArrayList<>();
        for (int size : SIZES) {
            payloads.add(payload(size));
        }

        StringBuilder seqRows = new StringBuilder();
        StringBuilder concRows = new StringBuilder();
        for (Payload p : payloads) {
            for (String method : List.of("multipart", "presigned")) {
                for (int i = 0; i < WARMUP; i++) {
                    record(method, p, "warmup", i, upload(method, p, true));
                }
                flushCsv();

                List<Upload> uploads = new ArrayList<>();
                for (int i = 0; i < RUNS; i++) {
                    Upload u = upload(method, p, true);
                    record(method, p, "seq", i, u);
                    uploads.add(u);
                }
                flushCsv();
                List<Long> times = successTimes(uploads);
                Upload firstOk = uploads.stream().filter(Upload::ok).findFirst().orElse(null);
                seqRows.append("| ").append(method).append(" | ").append(mb(p.size())).append(" | ").append(RUNS)
                        .append(" | ").append(times.size()).append(" | ").append(RUNS - times.size())
                        .append(" | ").append(failureKinds(uploads))
                        .append(" | ").append(percentileOrDash(times, 50)).append(" | ").append(percentileOrDash(times, 95))
                        .append(" | ").append(firstOk == null ? "-" : firstOk.clientRoundTrips())
                        .append(" | ").append(firstOk == null ? "-" : firstOk.r2Calls()).append(" |\n");
                printPhase(method, p, "seq", uploads);

                Concurrent c = concurrent(method, p);
                flushCsv();
                List<Long> concTimes = successTimes(c.uploads());
                concRows.append("| ").append(method).append(" | ").append(mb(p.size())).append(" | ").append(CONCURRENCY)
                        .append(" | ").append(concTimes.size()).append(" | ").append(CONCURRENCY - concTimes.size())
                        .append(" | ").append(failureKinds(c.uploads()))
                        .append(" | ").append(percentileOrDash(concTimes, 50))
                        .append(" | ").append(concTimes.isEmpty() ? "-" : PerfSupport.max(concTimes))
                        .append(" | ").append(c.wallMs())
                        .append(" | ").append(mb(c.heapBaseline())).append(" | ").append(mb(c.heapPeak()))
                        .append(" | ").append(mb(c.heapPeak() - c.heapBaseline())).append(" |\n");
                printPhase(method, p, "concurrent", c.uploads());
            }
        }

        // 완료 알림을 일부러 빠뜨린다: presign + PUT 만 하고 complete 를 부르지 않는다
        Payload small = payloads.getFirst();
        List<Upload> orphanUploads = new ArrayList<>();
        for (int i = 0; i < ORPHANS; i++) {
            Upload u = upload("presigned", small, false);
            record("presigned", small, "orphan", i, u);
            orphanUploads.add(u);
        }
        flushCsv();
        printPhase("presigned", small, "orphan", orphanUploads);
        List<String> presignedObjects = controller.listKeys(PREFIX + runId + "/presigned/");
        long orphans = presignedObjects.stream().filter(k -> !controller.completedKeys().contains(k)).count();
        long totalBeforeCleanup = controller.listKeys(PREFIX).size();
        int deleted = controller.deleteAllUnderPrefix();
        long afterCleanup = controller.listKeys(PREFIX).size();
        System.out.println("[DocUploadR2PerfTest] 끝 정리: perf/ 객체 " + totalBeforeCleanup + "건 중 " + deleted
                + "건 지움, 남은 " + afterCleanup + "건");

        writeReport(payloads, seqRows, concRows, orphans, leftover, totalBeforeCleanup, deleted, afterCleanup);
        if (afterCleanup != 0) {
            throw new IllegalStateException("끝 정리 후에도 perf/ 에 " + afterCleanup + "건 남음");
        }
    }

    // ---------- 업로드 ----------

    /** 예외를 밖으로 던지지 않는다. 실패도 한 건으로 기록하고 재시도하지 않는다. */
    private Upload upload(String method, Payload p, boolean complete) {
        long r2Before = controller.r2Calls();
        long started = System.nanoTime();
        int[] roundTrips = {0};
        try {
            return attempt(method, p, complete, r2Before, started, roundTrips);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            return new Upload(elapsed, roundTrips[0], controller.r2Calls() - r2Before, 0, "", errorKind(e));
        }
    }

    private Upload attempt(String method, Payload p, boolean complete, long r2Before, long started, int[] roundTrips)
            throws Exception {
        int status;
        String key;
        if (method.equals("multipart")) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(spring("/perf/doc-upload/multipart"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", bearer)
                    .header("X-Content-Sha256", p.sha256())
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(p.multipartBody())).build(),
                    HttpResponse.BodyHandlers.ofString());
            roundTrips[0]++;
            status = r.statusCode();
            key = status == 200 ? MAPPER.readTree(r.body()).path("key").asString() : "";
        } else {
            HttpResponse<String> presign = postJson("/perf/doc-upload/presign", Map.of(
                    "contentType", "image/jpeg", "size", p.size(), "sha256", p.sha256()));
            roundTrips[0]++;
            if (presign.statusCode() != 200) {
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                return new Upload(elapsed, roundTrips[0], controller.r2Calls() - r2Before, presign.statusCode(), "", "");
            }
            JsonNode body = MAPPER.readTree(presign.body());
            key = body.path("key").asString();
            HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(body.path("url").asString()))
                    .timeout(Duration.ofSeconds(60))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(p.jpeg()));
            // 서명에 들어간 헤더를 그대로 보낸다(host·content-length 는 HttpClient 가 넣는다)
            body.path("headers").properties().forEach(e -> {
                String name = e.getKey().toLowerCase();
                if (!name.equals("host") && !name.equals("content-length")) {
                    e.getValue().valueStream().forEach(v -> put.header(e.getKey(), v.asString()));
                }
            });
            HttpResponse<String> r2 = HTTP.send(put.build(), HttpResponse.BodyHandlers.ofString());
            roundTrips[0]++;
            status = r2.statusCode();
            if (complete && status == 200) {
                HttpResponse<String> done = postJson("/perf/doc-upload/complete", Map.of("key", key, "size", p.size()));
                roundTrips[0]++;
                status = done.statusCode();
            }
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return new Upload(elapsed, roundTrips[0], controller.r2Calls() - r2Before, status, key, "");
    }

    /** 예외 종류만 남긴다(가장 안쪽 원인 클래스 + 짧은 메시지). URL·서명이 섞이지 않게 메시지는 80자로 자르고 쉼표·줄바꿈을 지운다. */
    private static String errorKind(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? "" : root.getMessage();
        int query = message.indexOf('?');
        if (query >= 0) {
            message = message.substring(0, query);
        }
        message = message.replaceAll("[,\\r\\n;|]", " ").trim();
        if (message.length() > 80) {
            message = message.substring(0, 80);
        }
        return root.getClass().getSimpleName() + (message.isEmpty() ? "" : " " + message);
    }

    private Concurrent concurrent(String method, Payload p) throws Exception {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        System.gc();
        Thread.sleep(200);
        long baseline = memory.getHeapMemoryUsage().getUsed();
        AtomicLong peak = new AtomicLong(baseline);
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (sampling.get()) {
                peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        CountDownLatch go = new CountDownLatch(1);
        List<Future<Upload>> futures = new ArrayList<>();
        long started;
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY)) {
            for (int i = 0; i < CONCURRENCY; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return upload(method, p, true);
                }));
            }
            started = System.nanoTime();
            go.countDown();
        }
        long wall = (System.nanoTime() - started) / 1_000_000;
        sampling.set(false);
        sampler.join();
        // upload() 는 예외를 던지지 않으므로 여기서 한 건 실패로 단계 전체가 끝나지 않는다
        List<Upload> uploads = new ArrayList<>();
        int seq = 0;
        for (Future<Upload> f : futures) {
            Upload u = f.get();
            record(method, p, "concurrent", seq++, u);
            uploads.add(u);
        }
        return new Concurrent(wall, baseline, peak.get(), uploads);
    }

    private HttpResponse<String> postJson(String path, Map<String, Object> body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(spring(path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", bearer).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI spring(String path) {
        return URI.create("http://localhost:" + port + path + (path.contains("?") ? "&" : "?") + "run=" + runId);
    }

    private void record(String method, Payload p, String phase, int seq, Upload u) {
        csv.add(method + "," + p.size() + "," + phase + "," + seq + "," + u.elapsedMs() + "," + u.clientRoundTrips()
                + "," + u.r2Calls() + "," + u.status() + "," + u.error());
    }

    /** 모아 둔 줄을 raw.csv 끝에 덧붙이고 비운다. */
    private void flushCsv() throws IOException {
        List<String> lines;
        synchronized (csv) {
            lines = new ArrayList<>(csv);
            csv.clear();
        }
        if (!lines.isEmpty()) {
            Files.writeString(RAW, String.join("\n", lines) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static List<Long> successTimes(List<Upload> uploads) {
        return uploads.stream().filter(Upload::ok).map(Upload::elapsedMs).toList();
    }

    /** 실패 종류별 건수. 예: "SSLException (bad_record_mac) Received fatal alert: bad_record_mac ×2". 실패 없으면 "-". */
    private static String failureKinds(List<Upload> uploads) {
        Map<String, Long> kinds = new TreeMap<>();
        uploads.stream().filter(u -> !u.ok()).forEach(u -> kinds.merge(u.failureKind(), 1L, Long::sum));
        if (kinds.isEmpty()) {
            return "-";
        }
        return kinds.entrySet().stream().map(e -> e.getKey() + " ×" + e.getValue())
                .reduce((a, b) -> a + "<br>" + b).orElse("-");
    }

    private static void printPhase(String method, Payload p, String phase, List<Upload> uploads) {
        long ok = uploads.stream().filter(Upload::ok).count();
        System.out.println("[DocUploadR2PerfTest] " + method + " " + mb(p.size()) + " " + phase + ": 성공 " + ok
                + ", 실패 " + (uploads.size() - ok) + ", 실패 종류 " + failureKinds(uploads).replace("<br>", "; "));
    }

    // ---------- 합성 JPEG ----------

    /** "SAMPLE" 글자를 그린 JPEG 를 만들고, SOI 바로 뒤에 COM 세그먼트를 넣어 정확히 size 바이트로 맞춘다. */
    static byte[] syntheticJpeg(int size) throws IOException {
        System.setProperty("java.awt.headless", "true");
        BufferedImage image = new BufferedImage(1000, 630, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(220, 230, 240), 1000, 630, new Color(180, 190, 210)));
        g.fillRect(0, 0, 1000, 630);
        g.setColor(new Color(120, 20, 20));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 180));
        g.drawString("SAMPLE", 120, 380);
        g.dispose();
        ByteArrayOutputStream base = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", base);
        byte[] jpeg = base.toByteArray();
        if (jpeg.length + 4 > size) {
            throw new IllegalStateException("base jpeg too large: " + jpeg.length);
        }
        List<Integer> segments = new ArrayList<>();
        int remaining = size - jpeg.length;
        while (remaining > 0) {
            int take = Math.min(65537, remaining);   // 마커 2 + 길이 2 + 데이터 최대 65533
            if (remaining - take > 0 && remaining - take < 4) {
                take -= 4;
            }
            segments.add(take);
            remaining -= take;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        out.write(jpeg, 0, 2);   // SOI
        for (int total : segments) {
            int length = total - 2;
            out.write(0xFF);
            out.write(0xFE);
            out.write(length >> 8);
            out.write(length & 0xFF);
            out.write(new byte[total - 4]);
        }
        out.write(jpeg, 2, jpeg.length - 2);
        byte[] result = out.toByteArray();
        if (result.length != size || ImageIO.read(new ByteArrayInputStream(result)) == null) {
            throw new IllegalStateException("synthetic jpeg invalid: " + result.length);
        }
        return result;
    }

    private static Payload payload(int size) throws Exception {
        byte[] jpeg = syntheticJpeg(size);
        String sha = sha256(jpeg);
        ByteArrayOutputStream body = new ByteArrayOutputStream(size + 512);
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(jpeg);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return new Payload(size, jpeg, sha, body.toByteArray());
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 리포트 ----------

    private static long percentile(List<Long> values, int p) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    /** 성공 건이 없으면 "-". */
    private static String percentileOrDash(List<Long> values, int p) {
        return values.isEmpty() ? "-" : String.valueOf(percentile(values, p));
    }

    private static String mb(long bytes) {
        return String.format("%.1f MiB", bytes / 1024.0 / 1024.0);
    }

    private void writeReport(List<Payload> payloads, StringBuilder seqRows, StringBuilder concRows, long orphans,
                             int leftover, long totalBeforeCleanup, int deleted, long afterCleanup) throws Exception {
        long maxHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        StringBuilder md = new StringBuilder(PerfSupport.header(jdbc, "기준선 2 등록증 업로드 경로(multipart vs presigned, R2)",
                "합성 JPEG(\"SAMPLE\" 글자, COM 세그먼트로 크기 맞춤) " + payloads.stream().map(p -> mb(p.size())).toList()
                        + ", R2 버킷 R2_ARC_BUCKET 의 " + PREFIX + runId + "/ 아래",
                "경로·크기마다 워밍업 " + WARMUP + "회 + 순차 " + RUNS + "회, 동시 " + CONCURRENCY + "건 1회, 고아 " + ORPHANS + "건",
                new String[] {"클라이언트·Spring", "같은 JVM(Spring RANDOM_PORT, java.net.http.HttpClient). 힙은 JVM 전체 값"},
                new String[] {"JVM 최대 힙", mb(maxHeap)},
                new String[] {"AWS SDK", "software.amazon.awssdk:s3 2.59.1, path-style, checksum WHEN_REQUIRED"}));
        md.append("실패한 건은 재시도하지 않고 실패로 센다. 시간 통계(p50·p95·최대)는 성공한 건만으로 계산한다.\n\n");
        md.append("## 순차 업로드(끝까지 걸린 시간)\n\n")
                .append("| 경로 | 크기 | 횟수 | 성공 | 실패 | 실패 종류 | p50(ms) | p95(ms) | 클라이언트 왕복 | Spring→R2 호출 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n").append(seqRows);
        md.append("\n## 동시 ").append(CONCURRENCY).append("건\n\n")
                .append("| 경로 | 크기 | 건수 | 성공 | 실패 | 실패 종류 | 성공 건 p50(ms) | 성공 건 최대(ms) | 전체 시간(ms)"
                        + " | 시작 전 힙 | 최대 힙 | 증가 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n").append(concRows);
        md.append("\n## 완료 알림을 빠뜨렸을 때·정리\n\n")
                .append("| 항목 | 값 |\n|---|---|\n")
                .append("| 시작 전 perf/ 잔여 객체(이전 실행이 남긴 것, 시작 전에 지움) | ").append(leftover).append(" |\n")
                .append("| complete 없이 PUT 만 한 건수 | ").append(ORPHANS).append(" |\n")
                .append("| R2 에 남은 고아 객체(완료 알림 없는 presigned 객체) | ").append(orphans).append(" |\n")
                .append("| 정리 전 perf/ 객체 수 | ").append(totalBeforeCleanup).append(" |\n")
                .append("| 지운 객체 수 | ").append(deleted).append(" |\n")
                .append("| 정리 후 perf/ 객체 수 | ").append(afterCleanup).append(" |\n");
        md.append("\n원본: `").append(RAW).append("`\n");
        PerfSupport.write(REPORT, md.toString());
    }

    // ---------- 시제품 컨트롤러(test 전용) ----------

    /** 본 구현 전 시제품. 이 테스트만 @Import 하고, 다른 테스트의 컴포넌트 스캔에서는 빠진다(테스트 클래스 안의 클래스). */
    @RestController
    @RequestMapping("/perf/doc-upload")
    static class PrototypeUploadController {

        static final long MAX_BYTES = 10L * 1024 * 1024;
        static final Set<String> TYPES = Set.of("image/jpeg", "image/png");

        private final Environment env;
        private final AtomicLong r2Calls = new AtomicLong();
        private final Set<String> completed = ConcurrentHashMap.newKeySet();
        private volatile S3Client s3;
        private volatile S3Presigner presigner;

        PrototypeUploadController(Environment env) {
            this.env = env;
        }

        record PresignRequest(String contentType, long size, String sha256) {
        }

        record CompleteRequest(String key, long size) {
        }

        @PostMapping(path = "/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        Map<String, Object> multipart(@RequestPart("file") MultipartFile file,
                                      @RequestHeader("X-Content-Sha256") String sha256,
                                      @RequestParam("run") String run)
                throws IOException {
            check(file.getContentType(), file.getSize());
            byte[] bytes = file.getBytes();
            if (!isJpegOrPng(bytes)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not an image");
            }
            if (!sha256(bytes).equals(sha256)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sha256 mismatch");
            }
            String key = PREFIX + run + "/multipart/" + UUID.randomUUID() + ".jpg";
            client().putObject(PutObjectRequest.builder().bucket(bucket()).key(key).contentType(file.getContentType())
                    .contentLength((long) bytes.length).build(), software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
            r2Calls.incrementAndGet();
            return Map.of("key", key, "size", bytes.length);
        }

        @PostMapping("/presign")
        Map<String, Object> presign(@RequestBody PresignRequest request,
                                    @RequestParam("run") String run) {
            check(request.contentType(), request.size());
            String key = PREFIX + run + "/presigned/" + UUID.randomUUID() + ".jpg";
            PutObjectRequest put = PutObjectRequest.builder().bucket(bucket()).key(key)
                    .contentType(request.contentType()).contentLength(request.size()).build();
            PresignedPutObjectRequest signed = signer().presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5)).putObjectRequest(put).build());
            return Map.of("key", key, "url", signed.url().toString(), "headers", signed.signedHeaders());
        }

        @PostMapping("/complete")
        Map<String, Object> complete(@RequestBody CompleteRequest request) {
            if (request.key() == null || !request.key().startsWith(PREFIX) || !request.key().contains("/presigned/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad key");
            }
            HeadObjectResponse head = client().headObject(b -> b.bucket(bucket()).key(request.key()));
            r2Calls.incrementAndGet();
            if (head.contentLength() != request.size() || head.contentLength() > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "size mismatch");
            }
            completed.add(request.key());
            return Map.of("key", request.key(), "size", head.contentLength());
        }

        private static void check(String contentType, long size) {
            if (contentType == null || !TYPES.contains(contentType)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported type");
            }
            if (size <= 0 || size > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad size");
            }
        }

        private static boolean isJpegOrPng(byte[] b) {
            boolean jpeg = b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
            boolean png = b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            return jpeg || png;
        }

        // ---------- R2 ----------

        /** 비어 있는 접속 변수 이름. 등록증 버킷 전용 키만 쓴다(정책·상품 키 R2_ACCESS_KEY_ID 는 쓰지 않음). */
        List<String> missingVariables() {
            return REQUIRED_VARIABLES.stream().filter(this::blank).toList();
        }

        boolean configured() {
            return missingVariables().isEmpty();
        }

        long r2Calls() {
            return r2Calls.get();
        }

        Set<String> completedKeys() {
            return completed;
        }

        List<String> listKeys(String prefix) {
            List<String> keys = new ArrayList<>();
            String token = null;
            do {
                ListObjectsV2Response page = client().listObjectsV2(ListObjectsV2Request.builder().bucket(bucket())
                        .prefix(prefix).continuationToken(token).build());
                page.contents().stream().map(S3Object::key).forEach(keys::add);
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (token != null);
            return keys;
        }

        /** perf/ 아래 객체만 지운다. 지운 개수. */
        int deleteAllUnderPrefix() {
            if (!PREFIX.equals("perf/")) {
                throw new IllegalStateException("unexpected prefix " + PREFIX);
            }
            List<String> keys = listKeys(PREFIX).stream().filter(k -> k.startsWith(PREFIX)).toList();
            for (int i = 0; i < keys.size(); i += 1000) {
                List<ObjectIdentifier> ids = keys.subList(i, Math.min(keys.size(), i + 1000)).stream()
                        .map(k -> ObjectIdentifier.builder().key(k).build()).toList();
                client().deleteObjects(DeleteObjectsRequest.builder().bucket(bucket())
                        .delete(Delete.builder().objects(ids).quiet(true).build()).build());
            }
            return keys.size();
        }

        private String bucket() {
            return env.getProperty("R2_ARC_BUCKET");
        }

        private boolean blank(String name) {
            String v = env.getProperty(name);
            return v == null || v.isBlank();
        }

        private S3Client client() {
            if (s3 == null) {
                synchronized (this) {
                    if (s3 == null) {
                        s3 = S3Client.builder().endpointOverride(URI.create(env.getProperty("R2_ENDPOINT_URL")))
                                .region(region()).credentialsProvider(credentials())
                                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                                .build();
                    }
                }
            }
            return s3;
        }

        private S3Presigner signer() {
            if (presigner == null) {
                synchronized (this) {
                    if (presigner == null) {
                        presigner = S3Presigner.builder().endpointOverride(URI.create(env.getProperty("R2_ENDPOINT_URL")))
                                .region(region()).credentialsProvider(credentials())
                                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                                .build();
                    }
                }
            }
            return presigner;
        }

        private Region region() {
            return Region.of(env.getProperty("R2_REGION", "auto"));
        }

        private StaticCredentialsProvider credentials() {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    env.getProperty("R2_OCR_ACCESS_KEY_ID"), env.getProperty("R2_OCR_SECRET_ACCESS_KEY")));
        }
    }
}
