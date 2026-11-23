package com.hanstep.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.document.FakeArcStorageConfig;
import com.hanstep.document.FakeArcStorageConfig.FakeArcImageStorage;
import com.hanstep.support.TestUsers;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 등록증 원본 보관 기한 만료 삭제(#162). 문서마다 트랜잭션을 따로 쓰고 잠금을 겹쳐 봐야 해서 @Transactional 없이 커밋하고,
 * 넣은 행은 끝나고 지운다(사용자를 지우면 documents·document_fields·ai_jobs·visa_profiles 가 같이 지워진다).
 * 공용 DB 의 다른 문서를 건드리지 않게 기한·판정 시각을 2000년으로 둔다. R2 대신 메모리 저장소. 항목 값은 가상 인물(Reyes) 합성 데이터.
 */
@SpringBootTest
@Import(FakeArcStorageConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ArcOriginalExpiryTest {

    private static final Instant EXPIRED = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2000-01-02T00:00:00Z");
    private static final Instant NOT_YET = Instant.parse("2000-01-03T00:00:00Z");

    @Autowired
    ArcOriginalExpiryService expiryService;

    @Autowired
    DocumentReviewService reviewService;

    @Autowired
    FakeArcImageStorage storage;

    @Autowired
    JdbcTemplate jdbcTemplate;

    long userId;

    @BeforeEach
    void setUp() {
        storage.reset();
        userId = TestUsers.create(jdbcTemplate, "test-arc-expiry-" + UUID.randomUUID(), "MARK ANTHONY REYES");
    }

    @AfterEach
    void tearDown() {
        storage.reset();
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    // 1
    @Test
    void 기한_지난_미확정_문서는_R2_원본을_지우고_object_key_NULL_삭제_시각_항목_값을_비운다() {
        String key = storedKey();
        long id = insertDocument("PENDING_CONFIRMATION", key, EXPIRED);
        insertFields(id);
        List<Map<String, Object>> before = fieldMeta(id);

        scheduler(100).run(NOW);

        assertThat(storage.objects).doesNotContainKey(key);
        assertThat(storage.deletes.get()).isEqualTo(1);
        Map<String, Object> doc = document(id);
        assertThat(doc.get("object_key")).isNull();
        assertThat(doc.get("original_deleted_at")).isNotNull();
        // 문서 상태는 바꾸지 않는다
        assertThat(doc.get("status")).isEqualTo("PENDING_CONFIRMATION");
        assertThat(valuesLeft(id)).isZero();
        // 항목 status·confidence·is_masked 는 그대로
        assertThat(fieldMeta(id)).isEqualTo(before);
    }

    // 2
    @Test
    void 기한_안_지난_문서는_건드리지_않는다() {
        String key = storedKey();
        long id = insertDocument("PENDING_CONFIRMATION", key, NOT_YET);
        insertFields(id);

        scheduler(100).run(NOW);
        assertThat(expiryService.deleteOriginal(id, NOW)).isFalse();

        assertThat(storage.objects).containsKey(key);
        assertThat(storage.deletes.get()).isZero();
        assertThat(document(id).get("object_key")).isEqualTo(key);
        assertThat(document(id).get("original_deleted_at")).isNull();
        assertThat(valuesLeft(id)).isEqualTo(6);
    }

    // 3
    @Test
    void 확정된_문서는_건드리지_않는다() {
        long id = insertDocument("CONFIRMED", null, EXPIRED);
        jdbcTemplate.update("UPDATE documents SET confirmed_at = ?, original_deleted_at = ? WHERE id = ?",
                Timestamp.from(EXPIRED), Timestamp.from(EXPIRED), id);
        Map<String, Object> before = document(id);

        assertThat(expiryService.findExpired(NOW, 100)).doesNotContain(id);
        scheduler(100).run(NOW);
        assertThat(expiryService.deleteOriginal(id, NOW)).isFalse();

        assertThat(storage.deletes.get()).isZero();
        assertThat(document(id)).isEqualTo(before);
    }

    // 4
    @Test
    void OCR_실패로_기한이_지난_문서도_원본을_지운다() {
        String key = storedKey();
        long id = insertDocument("UPLOADED", key, EXPIRED);
        jdbcTemplate.update("""
                INSERT INTO ai_jobs (user_id, job_type, resource_type, resource_id, idempotency_key, correlation_id,
                  status, error_code, completed_at)
                VALUES (?, 'OCR', 'DOCUMENT', ?, ?, 'req_test', 'FAILED', 'OCR_FAILED', CURRENT_TIMESTAMP(3))
                """, userId, id, "test-" + UUID.randomUUID());

        scheduler(100).run(NOW);

        assertThat(storage.objects).doesNotContainKey(key);
        Map<String, Object> doc = document(id);
        assertThat(doc.get("object_key")).isNull();
        assertThat(doc.get("original_deleted_at")).isNotNull();
        assertThat(doc.get("status")).isEqualTo("UPLOADED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_jobs WHERE resource_id = ? AND job_type = 'OCR'",
                String.class, id)).isEqualTo("FAILED");
    }

    // 5
    @Test
    void R2_삭제가_실패하면_행은_그대로_두고_다음_실행에서_다시_처리하며_다른_문서는_처리한다(CapturedOutput output) {
        String failingKey = storedKey();
        long failing = insertDocument("PENDING_CONFIRMATION", failingKey, EXPIRED);
        insertFields(failing);
        String okKey = storedKey();
        long ok = insertDocument("PENDING_CONFIRMATION", okKey, EXPIRED);
        insertFields(ok);
        storage.failDeleteKeys.add(failingKey);

        scheduler(100).run(NOW);

        // 실패한 문서: 행 그대로(원본을 가리키고, 삭제 시각 없음, 값 그대로)
        assertThat(document(failing).get("object_key")).isEqualTo(failingKey);
        assertThat(document(failing).get("original_deleted_at")).isNull();
        assertThat(valuesLeft(failing)).isEqualTo(6);
        assertThat(storage.objects).containsKey(failingKey);
        // 다른 문서는 처리
        assertThat(document(ok).get("object_key")).isNull();
        assertThat(valuesLeft(ok)).isZero();
        assertThat(storage.objects).doesNotContainKey(okKey);
        assertThat(output.getOut()).contains("arc original expiry: expired=2, deleted=1, failed=1")
                .contains("arc original expiry failed: documentId=" + failing + ", reason=ArcStorageException");

        // R2 가 돌아오면 다음 실행에서 다시 처리
        storage.failDeleteKeys.clear();
        scheduler(100).run(NOW);

        assertThat(document(failing).get("object_key")).isNull();
        assertThat(document(failing).get("original_deleted_at")).isNotNull();
        assertThat(valuesLeft(failing)).isZero();
        assertThat(storage.objects).doesNotContainKey(failingKey);
        assertThat(output.getOut()).contains("arc original expiry: expired=1, deleted=1, failed=0");
    }

    // 6
    @Test
    void R2에_객체가_이미_없어도_성공으로_처리한다() {
        // 수명 주기 규칙(arc-expire-1d)이 먼저 지운 경우
        long id = insertDocument("PENDING_CONFIRMATION", "arc/front/" + UUID.randomUUID() + ".jpg", EXPIRED);
        insertFields(id);

        scheduler(100).run(NOW);

        assertThat(storage.deletes.get()).isEqualTo(1);
        assertThat(document(id).get("object_key")).isNull();
        assertThat(document(id).get("original_deleted_at")).isNotNull();
        assertThat(valuesLeft(id)).isZero();
    }

    // 7
    @Test
    void 같은_문서를_두_번_처리해도_한_번만_지운다(CapturedOutput output) {
        long id = insertDocument("PENDING_CONFIRMATION", storedKey(), EXPIRED);
        insertFields(id);

        assertThat(expiryService.deleteOriginal(id, NOW)).isTrue();
        Object deletedAt = document(id).get("original_deleted_at");
        assertThat(expiryService.deleteOriginal(id, NOW)).isFalse();
        scheduler(100).run(NOW);

        assertThat(storage.deletes.get()).isEqualTo(1);
        assertThat(document(id).get("original_deleted_at")).isEqualTo(deletedAt);
        // 대상이 없으면 요약 로그도 없다
        assertThat(output.getOut()).doesNotContain("arc original expiry:");
    }

    // 8
    @Test
    void 만료_삭제_중에_확정이_오면_잠금을_기다렸다가_만료만_반영된다() throws Exception {
        String key = storedKey();
        long id = insertDocument("PENDING_CONFIRMATION", key, EXPIRED);
        insertFields(id);

        Race race = race(() -> expiryService.deleteOriginal(id, NOW), () -> reviewService.confirm(userId, id));

        assertThat(race.first()).isEqualTo(Boolean.TRUE);
        // 확정은 만료가 커밋된 뒤의 행을 읽는다: 값이 비어 422
        assertThat(race.secondError()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) race.secondError()).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_DATA);
        assertOnlyOneApplied(id, false);
    }

    // 8
    @Test
    void 확정_중에_만료_삭제가_오면_잠금을_기다렸다가_확정만_반영된다() throws Exception {
        String key = storedKey();
        long id = insertDocument("PENDING_CONFIRMATION", key, EXPIRED);
        insertFields(id);

        Race race = race(() -> reviewService.confirm(userId, id), () -> expiryService.deleteOriginal(id, NOW));

        assertThat(race.firstError()).isNull();
        assertThat(race.secondError()).isNull();
        // 만료 삭제는 확정이 커밋된 뒤의 행을 읽는다: 원본이 이미 지워져 건너뜀
        assertThat(race.second()).isEqualTo(Boolean.FALSE);
        assertThat(storage.deletes.get()).isEqualTo(1);
        assertOnlyOneApplied(id, true);
    }

    // 9
    @Test
    void 상한을_넘는_대상은_상한만큼만_처리하고_나머지는_다음_실행에서_처리한다(CapturedOutput output) {
        long first = insertDocument("PENDING_CONFIRMATION", storedKey(), EXPIRED);
        long second = insertDocument("PENDING_CONFIRMATION", storedKey(), EXPIRED.plusSeconds(1));
        long third = insertDocument("PENDING_CONFIRMATION", storedKey(), EXPIRED.plusSeconds(2));

        scheduler(2).run(NOW);

        assertThat(document(first).get("object_key")).isNull();
        assertThat(document(second).get("object_key")).isNull();
        assertThat(document(third).get("object_key")).isNotNull();
        assertThat(storage.deletes.get()).isEqualTo(2);
        assertThat(output.getOut()).contains("arc original expiry: expired=2, deleted=2, failed=0");

        scheduler(2).run(NOW);

        assertThat(document(third).get("object_key")).isNull();
        assertThat(output.getOut()).contains("arc original expiry: expired=1, deleted=1, failed=0");
    }

    // 11
    @Test
    void 로그에는_문서_id와_건수_예외_클래스_이름만_남고_object_key와_항목_값은_없다(CapturedOutput output) {
        String failingKey = storedKey();
        long failing = insertDocument("PENDING_CONFIRMATION", failingKey, EXPIRED);
        insertFields(failing);
        String okKey = storedKey();
        long ok = insertDocument("PENDING_CONFIRMATION", okKey, EXPIRED);
        insertFields(ok);
        storage.failDeleteKeys.add(failingKey);

        scheduler(100).run(NOW);

        assertThat(output.getOut()).contains("arc original expiry: expired=2, deleted=1, failed=1")
                .contains("arc original expiry failed: documentId=" + failing + ", reason=ArcStorageException")
                .doesNotContain(failingKey, okKey, "arc/front/")
                // 예외 메시지·스택도 남기지 않는다
                .doesNotContain("fake delete failure")
                .doesNotContain("MARK ANTHONY REYES", "950815", "PHL", "1995-08-15", "E-9");
    }

    // ---------- helpers ----------

    private ArcOriginalExpiryScheduler scheduler(int batchSize) {
        return new ArcOriginalExpiryScheduler(expiryService, batchSize);
    }

    // 확정이 반영됐으면 문서 CONFIRMED·체류 프로필·원본 삭제가 같이, 만료가 반영됐으면 상태는 확인 대기 그대로이고 체류 프로필 없음
    private void assertOnlyOneApplied(long id, boolean confirmed) {
        Map<String, Object> doc = document(id);
        int profiles = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM visa_profiles WHERE source_document_id = ?",
                Integer.class, id);
        assertThat(doc.get("object_key")).isNull();
        assertThat(doc.get("original_deleted_at")).isNotNull();
        assertThat(valuesLeft(id)).isZero();
        if (confirmed) {
            assertThat(doc.get("status")).isEqualTo("CONFIRMED");
            assertThat(doc.get("confirmed_at")).isNotNull();
            assertThat(profiles).isEqualTo(1);
            // 확정이 바꾼 항목 상태(EXTRACTED → CONFIRMED)가 그대로
            assertThat(fieldStatus(id, "nameEn")).isEqualTo("CONFIRMED");
        } else {
            assertThat(doc.get("status")).isEqualTo("PENDING_CONFIRMATION");
            assertThat(doc.get("confirmed_at")).isNull();
            assertThat(profiles).isZero();
            assertThat(fieldStatus(id, "nameEn")).isEqualTo("EXTRACTED");
        }
    }

    private record Race(Object first, Throwable firstError, Object second, Throwable secondError) {
    }

    /**
     * first 가 문서 행을 잠근 채 R2 삭제에 들어가면 멈춰 두고 second 를 시작한다. second 가 잠금을 기다리는 동안 first 를 풀어
     * 커밋시킨다. 잠금이 없으면 second 가 first 의 커밋 전 상태를 읽고 둘 다 반영된다.
     */
    private Race race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch inDelete = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blocked = new AtomicBoolean();
        storage.beforeDelete = key -> {
            if (blocked.compareAndSet(false, true)) {
                inDelete.countDown();
                await(release);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(first);
            assertThat(inDelete.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> b = pool.submit(second);
            Thread.sleep(300);
            release.countDown();
            Object[] firstResult = result(a);
            Object[] secondResult = result(b);
            return new Race(firstResult[0], (Throwable) firstResult[1], secondResult[0], (Throwable) secondResult[1]);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static Object[] result(Future<?> future) throws Exception {
        try {
            return new Object[] {future.get(15, TimeUnit.SECONDS), null};
        } catch (ExecutionException e) {
            return new Object[] {null, e.getCause()};
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String storedKey() {
        String key = "arc/front/" + UUID.randomUUID() + ".jpg";
        storage.objects.put(key, new byte[] {1});
        return key;
    }

    private long insertDocument(String status, String objectKey, Instant retentionUntil) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO documents (user_id, document_type, side, object_key, content_type, file_size_bytes,
                      sha256, status, retention_until, confirmed_at)
                    VALUES (?, 'ARC', 'FRONT', ?, 'image/jpeg', 245760, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, objectKey);
            ps.setString(3, HexFormat.of().formatHex(bytes));
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.from(retentionUntil));
            ps.setTimestamp(6, status.equals("CONFIRMED") ? Timestamp.from(EXPIRED) : null);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    // OCR 결과 6항목 + 사용자가 넣은 체류 만료일(확정할 수 있는 상태). 6항목 모두 값이 있다
    private void insertFields(long documentId) {
        String stayExpiry = LocalDate.now(KstDates.KST).plusYears(1).toString();
        jdbcTemplate.update("""
                INSERT INTO document_fields (document_id, field_key, extracted_value, confidence, is_masked,
                  confirmed_value, status, confirmed_at)
                VALUES (?, 'nameEn', 'MARK ANTHONY REYES', 0.9812, FALSE, NULL, 'EXTRACTED', NULL),
                       (?, 'nationality', 'PHL', 0.9655, FALSE, NULL, 'EXTRACTED', NULL),
                       (?, 'birthDate', '1995-08-15', 0.9734, FALSE, NULL, 'EXTRACTED', NULL),
                       (?, 'visaType', 'E-9', 0.9521, FALSE, NULL, 'EXTRACTED', NULL),
                       (?, 'stayExpiry', NULL, NULL, FALSE, ?, 'CORRECTED', CURRENT_TIMESTAMP(3)),
                       (?, 'registrationNo', '950815-5******', 0.9907, TRUE, NULL, 'EXTRACTED', NULL)
                """, documentId, documentId, documentId, documentId, documentId, stayExpiry, documentId);
    }

    private Map<String, Object> document(long id) {
        return jdbcTemplate.queryForMap(
                "SELECT object_key, status, original_deleted_at, confirmed_at FROM documents WHERE id = ?", id);
    }

    private int valuesLeft(long documentId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_fields WHERE document_id = ? "
                + "AND (extracted_value IS NOT NULL OR confirmed_value IS NOT NULL)", Integer.class, documentId);
    }

    private List<Map<String, Object>> fieldMeta(long documentId) {
        return jdbcTemplate.queryForList("SELECT field_key, status, confidence, is_masked, confirmed_at "
                + "FROM document_fields WHERE document_id = ? ORDER BY field_key", documentId);
    }

    private String fieldStatus(long documentId, String fieldKey) {
        return jdbcTemplate.queryForObject("SELECT status FROM document_fields WHERE document_id = ? AND field_key = ?",
                String.class, documentId, fieldKey);
    }
}
