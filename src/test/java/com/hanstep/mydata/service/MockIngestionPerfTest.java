package com.hanstep.mydata.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.mydata.domain.MydataScope;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MYD-02 적재 측정(B안: 거래만 JdbcTemplate.batchUpdate 1,000건 묶음). 기본 test 에서는 빠진다: ./gradlew perfTest
 *
 * <p>사용자 N명을 만들고 같은 원천 파일을 사용자별로 적재한다(외부 키에 -u{i} 를 붙여 겹치지 않게).
 * 워밍업 1회 + 측정 N=100 은 5회, N=1,000 은 3회(B안도 같은 조건으로 잰다). 회차마다 배치·연결을 지우고(계좌·거래는 CASCADE) 시작한다.
 * 시간은 N명 적재 전체(ingest 호출 N번). INSERT 수는 MySQL Com_insert 의 측정 전후 차이(DB 가 실제로 받은 INSERT 문 수).
 * rewriteBatchedStatements 를 켜면 Hibernate Statistics 는 묶음 INSERT 를 세지 못해 참고값(JPA 엔티티 INSERT)으로만 찍는다.
 */
@Tag("perf")
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"})
class MockIngestionPerfTest {

    private static final String USER_PREFIX = "perf-";
    private static final List<MydataScope> SCOPES =
            List.of(MydataScope.ACCOUNT_BALANCE, MydataScope.TRANSACTIONS, MydataScope.CARD_USAGE);
    private static final int WARMUP = 1;
    private static final String LABEL = "B (JdbcTemplate batchUpdate 1,000)";
    private static final Path REPORT = Path.of("build", "perf", "mydata-ingestion-b.md");

    @Autowired
    private MockIngestionService ingestionService;

    @Autowired
    private MockMydataReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void 기준선_N100() throws IOException {
        measure(100, 5);
    }

    @Test
    void 기준선_N1000() throws IOException {
        measure(1_000, 3);
    }

    private void measure(int n, int runs) throws IOException {
        deletePerfUsers();
        try {
            List<long[]> users = createUsers(n); // {userId, consentId}
            MockMydataReader.Source bank = reader.read("SHINHAN_BANK").orElseThrow();
            MockMydataReader.Source card = reader.read("SHINHAN_CARD").orElseThrow();
            Long bankId = institutionId("SHINHAN_BANK");
            Long cardId = institutionId("SHINHAN_CARD");
            int perUser = txnCount(bank) + txnCount(card);

            List<List<MockIngestionService.Target>> targets = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String suffix = "-u" + i;
                targets.add(List.of(new MockIngestionService.Target(bankId, withSuffix(bank, suffix)),
                        new MockIngestionService.Target(cardId, withSuffix(card, suffix))));
            }

            Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            List<Long> millis = new ArrayList<>();
            List<Long> inserts = new ArrayList<>();
            for (int round = 0; round < WARMUP + runs; round++) {
                cleanIngestedData();
                stats.clear();
                long comInsertBefore = comInsert();
                long started = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    long[] user = users.get(i);
                    ingestionService.ingest(user[0], "perf-key", user[1], SCOPES, 12, targets.get(i));
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                long insertCount = comInsert() - comInsertBefore;

                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM financial_transactions t "
                        + "JOIN users u ON u.id = t.user_id WHERE u.external_user_key LIKE 'perf-%'", Long.class))
                        .isEqualTo((long) n * perUser);
                System.out.printf("[perf] N=%d round=%d%s %d ms, Com_insert %d (Hibernate entityInsert %d)%n", n,
                        round, round < WARMUP ? "(warmup)" : "", elapsed, insertCount, stats.getEntityInsertCount());
                if (round >= WARMUP) {
                    millis.add(elapsed);
                    inserts.add(insertCount);
                }
            }
            report(n, (long) n * perUser, millis, inserts);
        } finally {
            deletePerfUsers();
        }
    }

    private void report(int n, long txns, List<Long> millis, List<Long> inserts) throws IOException {
        List<Long> sorted = new ArrayList<>(millis);
        Collections.sort(sorted);
        long median = sorted.get(sorted.size() / 2);
        long max = sorted.get(sorted.size() - 1);
        String insertText = inserts.stream().distinct().count() == 1 ? String.valueOf(inserts.get(0))
                : inserts.toString();
        String env = "MySQL " + jdbcTemplate.queryForObject("SELECT VERSION()", String.class)
                + " (local Docker), " + Runtime.getRuntime().availableProcessors() + " logical CPUs, "
                + System.getProperty("os.name") + ", Java " + System.getProperty("java.version");
        String row = String.format("| %s | %d | %d | %d | %d | %s | %s | runs(ms)=%s |",
                LABEL, n, txns, median, max, insertText, env, millis);
        System.out.println("[perf] " + row);

        Files.createDirectories(REPORT.getParent());
        if (!Files.exists(REPORT)) {
            Files.writeString(REPORT, "| 방식 | N | 거래 건수 | 중앙값 ms | 최댓값 ms | INSERT 수(Com_insert 차이) | 측정 환경 | 회차 |\n"
                    + "|---|---|---|---|---|---|---|---|\n", StandardCharsets.UTF_8);
        }
        Files.writeString(REPORT, row + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /** 서버 전체 INSERT 문 누적 수. 측정 중에는 이 테스트만 DB 를 쓴다. */
    private long comInsert() {
        return jdbcTemplate.queryForObject("SHOW GLOBAL STATUS LIKE 'Com_insert'", (rs, i) -> rs.getLong(2));
    }

    /** N명과 MYDATA 동의를 만든다(측정 밖). */
    private List<long[]> createUsers(int n) {
        List<Object[]> userRows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            userRows.add(new Object[] {USER_PREFIX + i, "PERF USER " + i});
        }
        jdbcTemplate.batchUpdate("INSERT INTO users (external_user_key, display_name) VALUES (?, ?)", userRows);
        List<Long> userIds = jdbcTemplate.queryForList("SELECT id FROM users WHERE external_user_key LIKE 'perf-%' "
                + "ORDER BY id", Long.class);
        List<Object[]> consentRows = userIds.stream().map(id -> new Object[] {id}).toList();
        jdbcTemplate.batchUpdate("INSERT INTO user_consents (user_id, consent_type, policy_version, status, "
                + "granted_at) VALUES (?, 'MYDATA', 'perf', 'GRANTED', NOW(3))", consentRows);
        return jdbcTemplate.query("SELECT u.id, c.id FROM users u JOIN user_consents c ON c.user_id = u.id "
                        + "WHERE u.external_user_key LIKE 'perf-%' ORDER BY u.id",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)});
    }

    private void cleanIngestedData() {
        jdbcTemplate.update("DELETE b FROM ingestion_batches b JOIN users u ON u.id = b.user_id "
                + "WHERE u.external_user_key LIKE 'perf-%'");
        jdbcTemplate.update("DELETE c FROM data_connections c JOIN users u ON u.id = c.user_id "
                + "WHERE u.external_user_key LIKE 'perf-%'");
    }

    private void deletePerfUsers() {
        cleanIngestedData();
        jdbcTemplate.update("DELETE FROM users WHERE external_user_key LIKE 'perf-%'");
    }

    private Long institutionId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM institutions WHERE institution_code = ?", Long.class,
                code);
    }

    private static int txnCount(MockMydataReader.Source source) {
        return source.accounts().stream().mapToInt(a -> a.transactions().size()).sum();
    }

    /** 같은 원천을 사용자별로 쓰되 외부 계좌·거래 키에 사용자 구분값을 붙인다. */
    private static MockMydataReader.Source withSuffix(MockMydataReader.Source source, String suffix) {
        List<MockMydataReader.Account> accounts = source.accounts().stream()
                .map(a -> new MockMydataReader.Account(a.externalKey() + suffix, a.accountNoMasked(), a.accountType(),
                        a.productName(), a.currency(), a.balance(), a.balanceAsOf(), a.salaryAccount(),
                        a.openedOn(), a.transactions().stream()
                        .map(t -> new MockMydataReader.Transaction(t.externalKey() + suffix, t.transactedAt(),
                                t.bookedDate(), t.direction(), t.amount(), t.currency(), t.counterpartyName(),
                                t.counterpartyBank(), t.memo(), t.categoryCode(), t.transactionKind()))
                        .toList()))
                .toList();
        return new MockMydataReader.Source(source.orgCode(), source.coverageFrom(), source.coverageTo(), accounts);
    }
}
