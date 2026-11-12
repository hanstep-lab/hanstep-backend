package com.hanstep.policy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.common.time.KstDates;
import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.domain.ReviewStatus;
import com.hanstep.policy.dto.PolicyDocumentReviewRequest;
import com.hanstep.policy.repository.PolicyDocumentRepository;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
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
 * 같은 법령의 두 새 버전을 동시에 승인(#42). 트랜잭션을 실제로 나눠야 해서 @Transactional 없이 커밋하고, 넣은 행은 끝나고 지운다.
 */
@SpringBootTest
class PolicyDocumentReviewConcurrencyTest {

    private static final LocalDate TODAY = LocalDate.now(KstDates.KST);

    @Autowired
    private PolicyDocumentReviewService service;

    @Autowired
    private PolicyDocumentRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String externalId;
    private long adminId;
    private long current;
    private long next;
    private long later;

    @BeforeEach
    void setUp() {
        externalId = "LAW-CONC-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO users (external_user_key, display_name, role) VALUES (?, 'TEST', 'ADMIN')",
                externalId);
        adminId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class,
                externalId);
        current = insertLaw("APPROVED", TODAY.minusDays(100));
        next = insertLaw("DRAFT", TODAY.plusDays(10));
        later = insertLaw("DRAFT", TODAY.plusDays(20));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM policy_document_reviews WHERE reviewed_by = ?", adminId);
        jdbcTemplate.update("DELETE FROM policy_documents WHERE external_id = ?", externalId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", adminId);
    }

    @Test
    void 두_새_버전을_동시에_승인해도_검색되는_기간이_겹치지_않는다() throws Exception {
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 첫 승인: 정리까지 내려보낸 뒤 커밋하지 않고 기다린다
            Future<?> first = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                service.review(adminId, next, approve());
                entityManager.flush();
                firstWritten.countDown();
                await(releaseFirst);
            }));
            assertThat(firstWritten.await(10, TimeUnit.SECONDS)).isTrue();

            // 두 번째 승인: 첫 승인이 커밋되기 전에 시작한다(잠그지 않으면 next 를 아직 DRAFT 로 보고 겹치게 정리함)
            Future<?> second = pool.submit(() -> service.review(adminId, later, approve()));
            Thread.sleep(300);
            releaseFirst.countDown();

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        List<PolicyDocument> versions = List.of(reload(current), reload(next), reload(later));
        assertThat(versions).allMatch(d -> d.getReviewStatus() == ReviewStatus.APPROVED);
        // 어느 날이든 검색되는 버전은 하나: current ~10일 뒤, next 10일 뒤~20일 뒤, later 20일 뒤~
        for (int days : new int[] {9, 10, 19, 20}) {
            LocalDate day = TODAY.plusDays(days);
            assertThat(versions.stream().filter(d -> d.isSearchable(day)).count()).as("day +%d", days).isEqualTo(1);
        }
        assertThat(reload(next).getEffectiveTo()).isEqualTo(TODAY.plusDays(20));
    }

    private PolicyDocumentReviewRequest approve() {
        return new PolicyDocumentReviewRequest(ReviewStatus.APPROVED, null, null, null);
    }

    private PolicyDocument reload(long id) {
        return transactionTemplate.execute(status -> repository.findById(id).orElseThrow());
    }

    private long insertLaw(String status, LocalDate from) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO policy_documents (source_type, title, issuing_agency, source_url, external_id,
                      effective_from, policy_version, content_hash, review_status)
                    VALUES ('LAW', '동시성 테스트', '법제처', 'https://example.com', ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, externalId);
            ps.setDate(2, Date.valueOf(from));
            ps.setString(3, "CONC-" + UUID.randomUUID().toString().substring(0, 8));
            ps.setString(4, HexFormat.of().formatHex(bytes));
            ps.setString(5, status);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
