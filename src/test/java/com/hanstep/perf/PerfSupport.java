package com.hanstep.perf;

import com.hanstep.mydata.service.MockIngestionService;
import com.hanstep.mydata.service.MockMydataReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @Tag("perf") 측정 공통 도구. perf- 로 시작하는 사용자만 만들고 지움(데모 사용자·시드는 건드리지 않음).
 * 사용자 생성·정리·원천 복제 방식은 MockIngestionPerfTest 와 같음.
 */
public final class PerfSupport {

    public static final String USER_PREFIX = "perf-";

    private PerfSupport() {
    }

    /** perf- 사용자 n명과 MYDATA 동의 생성. {userId, consentId} 를 id 순으로. */
    public static List<long[]> createUsers(JdbcTemplate jdbc, int n) {
        List<Object[]> userRows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            userRows.add(new Object[] {USER_PREFIX + i, "PERF USER " + i});
        }
        jdbc.batchUpdate("INSERT INTO users (external_user_key, display_name) VALUES (?, ?)", userRows);
        List<Long> userIds = jdbc.queryForList("SELECT id FROM users WHERE external_user_key LIKE 'perf-%' "
                + "ORDER BY id", Long.class);
        jdbc.batchUpdate("INSERT INTO user_consents (user_id, consent_type, policy_version, status, granted_at) "
                + "VALUES (?, 'MYDATA', 'perf', 'GRANTED', NOW(3))",
                userIds.stream().map(id -> new Object[] {id}).toList());
        return jdbc.query("SELECT u.id, c.id FROM users u JOIN user_consents c ON c.user_id = u.id "
                        + "WHERE u.external_user_key LIKE 'perf-%' ORDER BY u.id",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)});
    }

    /** perf- 사용자의 배치·연결 삭제(계좌·거래는 CASCADE). */
    public static void cleanIngestedData(JdbcTemplate jdbc) {
        jdbc.update("DELETE b FROM ingestion_batches b JOIN users u ON u.id = b.user_id "
                + "WHERE u.external_user_key LIKE 'perf-%'");
        jdbc.update("DELETE c FROM data_connections c JOIN users u ON u.id = c.user_id "
                + "WHERE u.external_user_key LIKE 'perf-%'");
    }

    /** perf- 사용자와 그 데이터 전부 삭제(나머지는 CASCADE). */
    public static void deletePerfUsers(JdbcTemplate jdbc) {
        cleanIngestedData(jdbc);
        jdbc.update("DELETE FROM users WHERE external_user_key LIKE 'perf-%'");
    }

    /** 사용자별 적재 대상(신한은행·신한카드 원천, 외부 키에 -u{i}). */
    public static List<List<MockIngestionService.Target>> targets(JdbcTemplate jdbc, MockMydataReader reader, int n) {
        MockMydataReader.Source bank = reader.read("SHINHAN_BANK").orElseThrow();
        MockMydataReader.Source card = reader.read("SHINHAN_CARD").orElseThrow();
        Long bankId = institutionId(jdbc, "SHINHAN_BANK");
        Long cardId = institutionId(jdbc, "SHINHAN_CARD");
        List<List<MockIngestionService.Target>> targets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String suffix = "-u" + i;
            targets.add(List.of(new MockIngestionService.Target(bankId, withSuffix(bank, suffix)),
                    new MockIngestionService.Target(cardId, withSuffix(card, suffix))));
        }
        return targets;
    }

    public static long perfTransactionCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM financial_transactions t JOIN users u ON u.id = t.user_id "
                + "WHERE u.external_user_key LIKE 'perf-%'", Long.class);
    }

    /** 서버 전역 상태 카운터(Com_insert, Com_select 등). SHOW 문은 Com_select·Com_insert 에 세지 않음. */
    public static long globalStatus(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("SHOW GLOBAL STATUS LIKE ?", (rs, i) -> rs.getLong(2), name);
    }

    /** 짝수 개면 가운데 두 값의 평균. 평균은 정수 나눗셈이라 소수점 아래를 버린다(측정값이 양수라 내림과 같음). */
    public static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    public static long max(List<Long> values) {
        return Collections.max(values);
    }

    /** 리포트 맨 위 측정 조건. extraRows 는 {"항목", "값"} 쌍으로 표에 덧붙임. */
    public static String header(JdbcTemplate jdbc, String title, String data, String repeat, String[]... extraRows) {
        StringBuilder extra = new StringBuilder();
        for (String[] row : extraRows) {
            extra.append("| ").append(row[0]).append(" | ").append(row[1]).append(" |\n");
        }
        return "# " + title + "\n\n"
                + "## 측정 조건\n\n"
                + "| 항목 | 값 |\n|---|---|\n"
                + "| 측정 시각 | " + OffsetDateTime.now(ZoneOffset.ofHours(9)).truncatedTo(ChronoUnit.SECONDS) + " |\n"
                + "| OS | " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " |\n"
                + "| MySQL | " + jdbc.queryForObject("SELECT VERSION()", String.class) + " (local Docker) |\n"
                + "| CPU | " + Runtime.getRuntime().availableProcessors() + " logical |\n"
                + "| Java | " + System.getProperty("java.version") + " |\n"
                + "| 데이터 | " + data + " |\n"
                + "| 반복 | " + repeat + " |\n" + extra + "\n";
    }

    public static void write(Path report, String content) throws IOException {
        Files.createDirectories(report.getParent());
        Files.writeString(report, content, StandardCharsets.UTF_8);
        System.out.println("[perf] report: " + report.toAbsolutePath());
    }

    private static Long institutionId(JdbcTemplate jdbc, String code) {
        return jdbc.queryForObject("SELECT id FROM institutions WHERE institution_code = ?", Long.class, code);
    }

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
