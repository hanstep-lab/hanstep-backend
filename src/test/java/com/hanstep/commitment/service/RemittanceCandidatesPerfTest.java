package com.hanstep.commitment.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hanstep.commitment.dto.RemittanceCandidateListResponse;
import com.hanstep.mydata.domain.MydataScope;
import com.hanstep.mydata.service.MockIngestionService;
import com.hanstep.mydata.service.MockMydataReader;
import com.hanstep.perf.PerfSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CMT-03 기준선(측정만, 인덱스·쿼리 변경 없음). 기본 test 에서는 빠짐: ./gradlew perfTest
 *
 * <p>perf- 사용자 1,000명을 MYD-02 로 적재(약 34만 건)하고 가운데 사용자 한 명으로
 * ① 두 쿼리의 EXPLAIN ANALYZE ② getRemittanceCandidates 워밍업 1회 + 20회 시간 ③ financial_transactions 인덱스 목록을 기록.
 * SQL 문장은 JdbcTemplate DEBUG 로그에서 그대로 가져오고(테스트 안에서만 로거 레벨 변경), 파라미터는 서비스와 같은 값으로 채움.
 */
@Tag("perf")
@SpringBootTest
class RemittanceCandidatesPerfTest {

    private static final int USERS = 1_000;
    private static final int WARMUP = 1;
    private static final int RUNS = 20;
    private static final Path REPORT = Path.of("build", "perf", "cmt03-remittance-candidates.md");
    private static final List<MydataScope> SCOPES =
            List.of(MydataScope.ACCOUNT_BALANCE, MydataScope.TRANSACTIONS, MydataScope.CARD_USAGE);

    @Autowired
    private CommitmentService commitmentService;

    @Autowired
    private MockIngestionService ingestionService;

    @Autowired
    private MockMydataReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void CMT03_기준선() throws IOException {
        PerfSupport.deletePerfUsers(jdbc);
        try {
            List<long[]> users = PerfSupport.createUsers(jdbc, USERS);
            List<List<MockIngestionService.Target>> targets = PerfSupport.targets(jdbc, reader, USERS);
            for (int i = 0; i < USERS; i++) {
                ingestionService.ingest(users.get(i)[0], "perf-key", users.get(i)[1], SCOPES, 12, targets.get(i));
            }
            long perfTxns = PerfSupport.perfTransactionCount(jdbc);
            long allTxns = jdbc.queryForObject("SELECT COUNT(*) FROM financial_transactions", Long.class);
            jdbc.execute("ANALYZE TABLE financial_transactions"); // 대량 적재 직후 통계 갱신(실행 계획 안정용)
            long userId = users.get(USERS / 2)[0];

            List<String> sqls = captureSql(userId);
            String batchSql = sqls.stream().filter(s -> s.contains("FROM ingestion_batches")).findFirst()
                    .orElseThrow(() -> new AssertionError("batch query not captured: " + sqls));
            String candidateSql = sqls.stream().filter(s -> s.contains("WITH txns AS")).findFirst()
                    .orElseThrow(() -> new AssertionError("candidate query not captured: " + sqls));
            // 서비스와 같은 값: 이 사용자의 최신 SUCCEEDED 배치 수집 기간
            LocalDate[] coverage = jdbc.queryForObject("SELECT coverage_from, coverage_to FROM ingestion_batches "
                            + "WHERE user_id = ? AND status = 'SUCCEEDED' ORDER BY id DESC LIMIT 1",
                    (rs, i) -> new LocalDate[] {rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)},
                    userId);
            LocalDate from = coverage[0];
            LocalDate to = coverage[1];

            String batchPlan = explainAnalyze(batchSql, userId);
            String candidatePlan = explainAnalyze(candidateSql, userId, from, to, userId, userId);

            List<Long> micros = new ArrayList<>();
            RemittanceCandidateListResponse last = null;
            for (int round = 0; round < WARMUP + RUNS; round++) {
                long started = System.nanoTime();
                last = commitmentService.getRemittanceCandidates(userId);
                long elapsedMicros = (System.nanoTime() - started) / 1_000;
                System.out.printf("[perf] CMT-03 round=%d%s %d us%n", round, round < WARMUP ? "(warmup)" : "",
                        elapsedMicros);
                if (round >= WARMUP) {
                    micros.add(elapsedMicros);
                }
            }
            assertThat(last.candidates()).hasSize(2);

            StringBuilder md = new StringBuilder(PerfSupport.header(jdbc, "CMT-03 송금 이력 Top 3 기준선",
                    String.format("perf- 사용자 %,d명 거래 %,d건 (financial_transactions 전체 %,d건), 측정 대상 userId=%d "
                            + "(분석 기간 %s ~ %s)", USERS, perfTxns, allTxns, userId, from, to),
                    "getRemittanceCandidates 워밍업 " + WARMUP + "회 + " + RUNS + "회(서비스 직접 호출, HTTP 제외)",
                    new String[] {"통계", "적재 직후 ANALYZE TABLE financial_transactions 실행(통계만 갱신)"}));
            md.append("## 실행 시간\n\n| 중앙값 | 최댓값 | 회차(µs) |\n|---|---|---|\n")
                    .append(String.format("| %.2f ms | %.2f ms | %s |%n%n", PerfSupport.median(micros) / 1000.0,
                            PerfSupport.max(micros) / 1000.0, micros));
            md.append("## 쿼리 1. 최신 SUCCEEDED 배치 조회\n\n```sql\n").append(batchSql.strip())
                    .append("\n```\n\n```\n").append(batchPlan).append("\n```\n\n");
            md.append("## 쿼리 2. 송금처 후보 조회(CTE·ROW_NUMBER)\n\n```sql\n").append(candidateSql.strip())
                    .append("\n```\n\n```\n").append(candidatePlan).append("\n```\n\n");
            md.append("## financial_transactions 인덱스(SHOW INDEX)\n\n")
                    .append("| Key_name | Seq | Column | Non_unique | Cardinality |\n|---|---|---|---|---|\n");
            jdbc.query("SHOW INDEX FROM financial_transactions", rs -> {
                md.append(String.format("| %s | %d | %s | %d | %d |%n", rs.getString("Key_name"),
                        rs.getInt("Seq_in_index"), rs.getString("Column_name"), rs.getInt("Non_unique"),
                        rs.getLong("Cardinality")));
            });
            md.append("\n## ingestion_batches 인덱스(SHOW INDEX)\n\n")
                    .append("| Key_name | Seq | Column | Non_unique | Cardinality |\n|---|---|---|---|---|\n");
            jdbc.query("SHOW INDEX FROM ingestion_batches", rs -> {
                md.append(String.format("| %s | %d | %s | %d | %d |%n", rs.getString("Key_name"),
                        rs.getInt("Seq_in_index"), rs.getString("Column_name"), rs.getInt("Non_unique"),
                        rs.getLong("Cardinality")));
            });
            PerfSupport.write(REPORT, md.toString());
        } finally {
            PerfSupport.deletePerfUsers(jdbc);
        }
    }

    /** getRemittanceCandidates 한 번이 실행한 SQL(JdbcTemplate DEBUG 로그). 로거 레벨은 끝나면 되돌림. */
    private List<String> captureSql(long userId) {
        Logger logger = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.JdbcTemplate");
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            commitmentService.getRemittanceCandidates(userId);
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Executing prepared SQL statement ["))
                .map(m -> m.substring(m.indexOf('[') + 1, m.lastIndexOf(']')))
                .toList();
    }

    private String explainAnalyze(String sql, Object... params) {
        return String.join("\n", jdbc.queryForList("EXPLAIN ANALYZE " + sql.strip(), String.class, params));
    }
}
