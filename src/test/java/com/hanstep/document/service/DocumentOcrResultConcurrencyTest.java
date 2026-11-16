package com.hanstep.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.dto.OcrFieldResult;
import com.hanstep.document.dto.OcrResultRequest;
import com.hanstep.support.TestUsers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HexFormat;
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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OCR 결과 동시 재전송. 트랜잭션을 실제로 나눠야 해서 @Transactional 없이 커밋하고, 넣은 행은 끝나고 지운다
 * (사용자를 지우면 documents·document_fields·ai_jobs 가 같이 지워진다).
 */
@SpringBootTest
class DocumentOcrResultConcurrencyTest {

    @Autowired
    private DocumentInternalService service;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;
    private long documentId;
    private long jobId;

    @BeforeEach
    void setUp() {
        userId = TestUsers.create(jdbcTemplate, "test-ocr-concurrency-" + UUID.randomUUID(), "MARK ANTHONY REYES");
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO documents (user_id, document_type, side, object_key, content_type, file_size_bytes,
                      sha256, status, retention_until)
                    VALUES (?, 'ARC', 'FRONT', 'arc/front/concurrency.jpg', 'image/jpeg', 245760, ?, 'OCR_PROCESSING',
                      CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, HexFormat.of().formatHex(bytes));
            return ps;
        }, keys);
        documentId = keys.getKey().longValue();
        jdbcTemplate.update("""
                INSERT INTO ai_jobs (user_id, job_type, resource_type, resource_id, idempotency_key, correlation_id,
                  status)
                VALUES (?, 'OCR', 'DOCUMENT', ?, ?, 'req_test', 'PROCESSING')
                """, userId, documentId, "test-" + UUID.randomUUID());
        jobId = jdbcTemplate.queryForObject("SELECT id FROM ai_jobs WHERE job_type = 'OCR' AND resource_id = ?",
                Long.class, documentId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void 같은_결과가_동시에_두_번_와도_한_번만_저장하고_둘_다_성공() throws Exception {
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 첫 보고: 쓰고 내려보낸 뒤 커밋하지 않고 기다린다
            Future<?> first = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                service.recordOcrResult(documentId, request());
                entityManager.flush();
                firstWritten.countDown();
                await(releaseFirst);
            }));
            assertThat(firstWritten.await(10, TimeUnit.SECONDS)).isTrue();

            // 재전송: 첫 보고가 커밋되기 전에 시작한다(잠금이 없으면 처리 전 상태를 읽고 항목을 한 번 더 넣다가 UNIQUE 충돌)
            Future<?> second = pool.submit(() -> service.recordOcrResult(documentId, request()));
            Thread.sleep(300);
            releaseFirst.countDown();

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_fields WHERE document_id = ?",
                Integer.class, documentId)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo("PENDING_CONFIRMATION");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_jobs WHERE id = ?", String.class, jobId))
                .isEqualTo("COMPLETED");
    }

    // 합성 데이터(가상 인물 Reyes)
    private OcrResultRequest request() {
        return new OcrResultRequest(documentId, "ppocrv5-ko-mobile-int8", 842, DocumentSide.FRONT, List.of(
                new OcrFieldResult("nameEn", "MARK ANTHONY REYES", new BigDecimal("0.9812"), false),
                new OcrFieldResult("nationality", "PHL", new BigDecimal("0.9655"), false),
                new OcrFieldResult("birthDate", "1995-08-15", new BigDecimal("0.9734"), false),
                new OcrFieldResult("visaType", "E-9", new BigDecimal("0.9521"), false),
                new OcrFieldResult("stayExpiry", null, null, false),
                new OcrFieldResult("registrationNo", "950815-5******", new BigDecimal("0.9907"), true)));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
