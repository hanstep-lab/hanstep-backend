package com.hanstep.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hanstep.aijob.messaging.AiJobMessage;
import com.hanstep.aijob.messaging.AiJobPublisher;
import com.hanstep.policy.domain.PolicySourceType;
import com.hanstep.policy.dto.LawDocumentRegisterRequest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AI-R6 동시 등록. 트랜잭션을 실제로 나눠야 해서 @Transactional 없이 커밋하고, 넣은 문서·작업은 끝나고 지운다.
 * 발행기는 가짜로 둔다(compose 브로커에 테스트 메시지를 넣지 않음). 실제 발행은 LawDocumentRegisterPublishTest.
 */
@SpringBootTest
class LawDocumentRegisterConcurrencyTest {

    private static final int THREADS = 5;

    @Autowired
    private LawDocumentRegisterService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiJobPublisher publisher;

    private final String hash = randomHash();

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ai_jobs WHERE job_type = 'POLICY_INGEST' AND idempotency_key = ?", hash);
        jdbcTemplate.update("DELETE FROM policy_documents WHERE content_hash = ?", hash);
    }

    @Test
    void 같은_해시를_동시에_보내도_행은_1개이고_모두_같은_ID() throws Exception {
        LawDocumentRegisterRequest request = new LawDocumentRegisterRequest(PolicySourceType.LAW,
                "출입국관리법 시행규칙", "법무부", "https://www.law.go.kr/test", "999999991", null,
                LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 23), hash, null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<LawDocumentRegisterService.Result>> futures = new ArrayList<>();
        List<LawDocumentRegisterService.Result> results = new ArrayList<>();
        try {
            Callable<LawDocumentRegisterService.Result> call = () -> {
                start.await(10, TimeUnit.SECONDS);
                return service.register(hash, request);
            };
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(call));
            }
            start.countDown();

            for (Future<LawDocumentRegisterService.Result> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).extracting(r -> r.document().policyDocumentId()).containsOnly(
                    results.getFirst().document().policyDocumentId());
            assertThat(results).filteredOn(LawDocumentRegisterService.Result::created).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM policy_documents WHERE content_hash = ?", Integer.class, hash);
        assertThat(count).isEqualTo(1);

        // 작업도 하나이고, 이긴 요청의 커밋 후에 한 번만 발행한다(진 요청은 문서·작업이 같이 롤백)
        List<Map<String, Object>> jobs = jdbcTemplate.queryForList(
                "SELECT * FROM ai_jobs WHERE job_type = 'POLICY_INGEST' AND idempotency_key = ?", hash);
        assertThat(jobs).hasSize(1);
        assertThat(((Number) jobs.getFirst().get("resource_id")).longValue())
                .isEqualTo(results.getFirst().document().policyDocumentId());
        assertThat(jobs.getFirst().get("publish_status")).isEqualTo("PUBLISHED");
        ArgumentCaptor<AiJobMessage> sent = ArgumentCaptor.forClass(AiJobMessage.class);
        verify(publisher, times(1)).publish(sent.capture());
        assertThat(sent.getValue().jobId()).isEqualTo(((Number) jobs.getFirst().get("id")).longValue());
    }

    private static String randomHash() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
