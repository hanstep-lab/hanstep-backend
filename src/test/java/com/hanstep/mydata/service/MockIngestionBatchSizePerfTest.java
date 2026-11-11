package com.hanstep.mydata.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.mydata.domain.MydataScope;
import com.hanstep.mydata.repository.DataConnectionRepository;
import com.hanstep.mydata.repository.FinancialAccountRepository;
import com.hanstep.mydata.repository.FinancialTransactionRepository;
import com.hanstep.mydata.repository.IngestionBatchRepository;
import com.hanstep.perf.PerfSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MYD-02 거래 묶음 크기(hanstep.mydata.txn-batch-size) 비교. 기본 test 에서는 빠짐: ./gradlew perfTest
 *
 * <p>MockIngestionPerfTest 의 N=100 과 같은 조건(perf- 사용자 100명, 워밍업 1회 + 5회 중앙값, Com_insert 차이)으로
 * 묶음 1·10·50·100·1,000 을 측정. 묶음은 ingest 한 번 안에서 계좌별로 나뉘므로 계좌당 최대 거래 수보다 큰 묶음은
 * 계좌마다 INSERT 1번으로 같음. 묶음 1 은 "JDBC 로 바꿨지만 묶지 않은 경우"(기존 A안 JPA saveAll 과 비교용).
 * 묶음 크기만 다르게 하려고 같은 빈들로 MockIngestionService 를 직접 만듦(서비스에 @Transactional 이 없어 프록시 차이 없음).
 * 사용자 1명 적재 시간은 묶음 1,000 기준 워밍업 1회 + 5회(참고용).
 */
@Tag("perf")
@SpringBootTest
class MockIngestionBatchSizePerfTest {

    private static final int USERS = 100;
    private static final int[] BATCH_SIZES = {1, 10, 50, 100, 1_000};
    private static final int WARMUP = 1;
    private static final int RUNS = 5;
    // ingest 1번에 거래 외 INSERT: 배치 1 + 연결 2 + 계좌 2 (MockIngestionPerfTest B안 Com_insert 700 = 100명 × (5 + 2))
    private static final int NON_TXN_INSERTS_PER_USER = 5;
    private static final Path REPORT = Path.of("build", "perf", "mydata-batch-size.md");
    private static final Path SAVE_ALL_REPORT = Path.of("build", "perf", "mydata-ingestion-a-remeasure.md");
    private static final List<MydataScope> SCOPES =
            List.of(MydataScope.ACCOUNT_BALANCE, MydataScope.TRANSACTIONS, MydataScope.CARD_USAGE);

    @Autowired
    private IngestionBatchRepository batchRepository;

    @Autowired
    private DataConnectionRepository connectionRepository;

    @Autowired
    private FinancialAccountRepository accountRepository;

    @Autowired
    private FinancialTransactionRepository transactionRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private MockMydataReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 묶음_크기별_적재() throws IOException {
        PerfSupport.deletePerfUsers(jdbc);
        try {
            List<long[]> users = PerfSupport.createUsers(jdbc, USERS);
            List<List<MockIngestionService.Target>> targets = PerfSupport.targets(jdbc, reader, USERS);
            List<Integer> accountSizes = targets.get(0).stream()
                    .flatMap(t -> t.source().accounts().stream())
                    .map(a -> a.transactions().size())
                    .toList();
            int perUser = accountSizes.stream().mapToInt(Integer::intValue).sum();

            List<String> rows = new ArrayList<>();
            Long maxPerAccount = null;
            for (int size : BATCH_SIZES) {
                MockIngestionService service = service(size);
                List<Long> millis = new ArrayList<>();
                List<Long> inserts = new ArrayList<>();
                for (int round = 0; round < WARMUP + RUNS; round++) {
                    PerfSupport.cleanIngestedData(jdbc);
                    long before = PerfSupport.globalStatus(jdbc, "Com_insert");
                    long started = System.nanoTime();
                    for (int i = 0; i < USERS; i++) {
                        service.ingest(users.get(i)[0], "perf-key", users.get(i)[1], SCOPES, 12, targets.get(i));
                    }
                    long elapsed = (System.nanoTime() - started) / 1_000_000;
                    long insertCount = PerfSupport.globalStatus(jdbc, "Com_insert") - before;
                    assertThat(PerfSupport.perfTransactionCount(jdbc)).isEqualTo((long) USERS * perUser);
                    System.out.printf("[perf] batch=%d round=%d%s %d ms, Com_insert %d%n", size, round,
                            round < WARMUP ? "(warmup)" : "", elapsed, insertCount);
                    if (round >= WARMUP) {
                        millis.add(elapsed);
                        inserts.add(insertCount);
                    }
                }
                if (maxPerAccount == null) {
                    maxPerAccount = maxTransactionsPerAccount(); // 적재된 DB 에서 실제로 셈
                }
                long expected = (long) USERS * (NON_TXN_INSERTS_PER_USER
                        + accountSizes.stream().mapToLong(n -> (n + size - 1) / size).sum());
                long median = PerfSupport.median(inserts);
                rows.add(String.format("| %,d%s | %,d | %,d | %s | %,d | %s | %s |", size,
                        size == 1 ? " (JDBC, 묶지 않음)" : size > maxPerAccount ? " (> 계좌당 최대)" : "",
                        PerfSupport.median(millis), PerfSupport.max(millis),
                        inserts.stream().distinct().count() == 1 ? String.format("%,d", inserts.get(0)) : inserts,
                        expected, median == expected ? "일치" : "불일치", millis));
            }

            // 사용자 1명(묶음 1,000): 첫 사용자만 반복 적재
            MockIngestionService service = service(1_000);
            List<Long> micros = new ArrayList<>();
            List<Long> singleInserts = new ArrayList<>();
            for (int round = 0; round < WARMUP + RUNS; round++) {
                PerfSupport.cleanIngestedData(jdbc);
                long before = PerfSupport.globalStatus(jdbc, "Com_insert");
                long started = System.nanoTime();
                service.ingest(users.get(0)[0], "perf-key", users.get(0)[1], SCOPES, 12, targets.get(0));
                long elapsed = (System.nanoTime() - started) / 1_000;
                long insertCount = PerfSupport.globalStatus(jdbc, "Com_insert") - before;
                System.out.printf("[perf] single user round=%d%s %d us, Com_insert %d%n", round,
                        round < WARMUP ? "(warmup)" : "", elapsed, insertCount);
                if (round >= WARMUP) {
                    micros.add(elapsed);
                    singleInserts.add(insertCount);
                }
            }

            StringBuilder md = new StringBuilder(PerfSupport.header(jdbc, "MYD-02 거래 묶음 크기별 적재 기준선",
                    String.format("perf- 사용자 %d명 × %d건 = 거래 %,d건(신한은행·신한카드 Mock 원천)", USERS, perUser,
                            (long) USERS * perUser),
                    "묶음 크기별 N=" + USERS + " 적재 워밍업 " + WARMUP + "회 + " + RUNS + "회, 사용자 1명 적재 워밍업 "
                            + WARMUP + "회 + " + RUNS + "회",
                    new String[] {"계좌별 거래 수(원천)", accountSizes.toString()},
                    new String[] {"계좌당 최대 거래 수(적재 후 DB 에서 셈)", String.valueOf(maxPerAccount)}));
            md.append("## 묶음 크기별 N=").append(USERS).append(" 적재\n\n")
                    .append("INSERT 계산값 = 사용자 수 × (거래 외 ").append(NON_TXN_INSERTS_PER_USER)
                    .append(" + Σ 계좌별 ⌈거래 수 ÷ 묶음 크기⌉). 계좌당 최대 거래 수(").append(maxPerAccount)
                    .append(")보다 큰 묶음은 계좌마다 1번이라 이보다 더 줄지 않음.\n\n")
                    .append("| 묶음 크기 | 중앙값 ms | 최댓값 ms | INSERT 수(Com_insert 차이) | INSERT 계산값 | 계산값과 | 회차(ms) |\n")
                    .append("|---|---|---|---|---|---|---|\n");
            rows.forEach(r -> md.append(r).append("\n"));
            md.append("\n## 비교: 기존 A안(JPA saveAll) N=100\n\n").append(saveAllReference()).append("\n");
            md.append("## 사용자 1명 적재(묶음 1,000, 참고용)\n\n")
                    .append("| 거래 건수 | 중앙값 | 최댓값 | INSERT 수(Com_insert 차이) | 회차(µs) |\n|---|---|---|---|---|\n")
                    .append(String.format("| %d | %.1f ms | %.1f ms | %s | %s |%n", perUser,
                            PerfSupport.median(micros) / 1000.0, PerfSupport.max(micros) / 1000.0,
                            singleInserts.stream().distinct().count() == 1 ? singleInserts.get(0) : singleInserts,
                            micros));
            PerfSupport.write(REPORT, md.toString());
        } finally {
            PerfSupport.deletePerfUsers(jdbc);
        }
    }

    private MockIngestionService service(int batchSize) {
        return new MockIngestionService(batchRepository, connectionRepository, accountRepository,
                transactionRepository, jdbc, tx, batchSize);
    }

    private long maxTransactionsPerAccount() {
        return jdbc.queryForObject("SELECT MAX(cnt) FROM (SELECT t.account_id, COUNT(*) AS cnt "
                + "FROM financial_transactions t JOIN users u ON u.id = t.user_id "
                + "WHERE u.external_user_key LIKE 'perf-%' GROUP BY t.account_id) x", Long.class);
    }

    /** 이전 측정 파일의 A안 N=100 행을 그대로 옮김(이번에 다시 재지 않음). */
    private static String saveAllReference() throws IOException {
        if (!Files.exists(SAVE_ALL_REPORT)) {
            return "이전 측정 파일(" + SAVE_ALL_REPORT + ")이 없어 비교값 없음.\n";
        }
        List<String> lines = Files.readAllLines(SAVE_ALL_REPORT, StandardCharsets.UTF_8);
        List<String> n100 = lines.stream().filter(l -> l.contains("JPA saveAll") && l.contains("| 100 |")).toList();
        if (n100.isEmpty()) {
            return "이전 측정 파일에 N=100 행이 없어 비교값 없음.\n";
        }
        return "출처: " + SAVE_ALL_REPORT + " (이번 실행에서 다시 재지 않은 이전 값, 측정 시점·DB 상태가 다름)\n\n"
                + lines.get(0) + "\n" + lines.get(1) + "\n" + String.join("\n", n100) + "\n";
    }
}
