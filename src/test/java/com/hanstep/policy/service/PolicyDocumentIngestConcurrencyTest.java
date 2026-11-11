package com.hanstep.policy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.policy.dto.PolicyIngestedRequest;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.HexFormat;
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
 * INT-13 동시 보고. 트랜잭션을 실제로 나눠야 해서 @Transactional 없이 커밋하고, 넣은 행은 끝나고 지운다.
 */
@SpringBootTest
class PolicyDocumentIngestConcurrencyTest {

    private static final String VERSION = "E74-CONCURRENCY-TEST";

    @Autowired
    private PolicyDocumentInternalService service;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String hash;
    private long id;

    @BeforeEach
    void setUp() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        hash = HexFormat.of().formatHex(bytes);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO policy_documents (source_type, title, issuing_agency, source_url,
                      policy_version, content_hash, review_status)
                    VALUES ('NOTICE', '동시성 테스트', '법무부', 'https://example.com', ?, ?, 'DRAFT')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, VERSION);
            ps.setString(2, hash);
            return ps;
        }, keys);
        id = keys.getKey().longValue();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM policy_documents WHERE id = ?", id);
    }

    @Test
    void 동시에_온_보고가_먼저_채운_object_key를_덮어쓰지_않는다() throws Exception {
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 첫 보고: 쓰고 내려보낸 뒤 커밋하지 않고 기다린다
            Future<?> first = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                service.recordIngested(id, request("policy-sources/first.pdf"));
                entityManager.flush();
                firstWritten.countDown();
                await(releaseFirst);
            }));
            assertThat(firstWritten.await(10, TimeUnit.SECONDS)).isTrue();

            // 두 번째 보고: 첫 보고가 커밋되기 전에 시작한다(잠금이 없으면 NULL 을 읽고 덮어씀)
            Future<?> second = pool.submit(() -> service.recordIngested(id, request("policy-sources/second.pdf")));
            Thread.sleep(300);
            releaseFirst.countDown();

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        String objectKey = jdbcTemplate.queryForObject(
                "SELECT object_key FROM policy_documents WHERE id = ?", String.class, id);
        assertThat(objectKey).isEqualTo("policy-sources/first.pdf");
    }

    private PolicyIngestedRequest request(String objectKey) {
        return new PolicyIngestedRequest(VERSION, hash, 7, "visa_policy_chunks", "BAAI/bge-m3",
                "5617a9f61b028005a4858fdac845db406aefb181", objectKey,
                OffsetDateTime.parse("2026-11-10T10:03:12+09:00"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
