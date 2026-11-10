package com.hanstep.onboarding.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.perf.PerfSupport;
import com.hanstep.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ONB-04 요청당 SQL 수 기준선(측정만). 기본 test 에서는 빠짐: ./gradlew perfTest
 *
 * <p>MockMvc 로 GET /api/v1/onboarding/summary 를 워밍업 1회 + 10회 호출. 요청마다 MySQL 전역 Com_select 전후 차이
 * (JdbcTemplate 쿼리 포함)와 Hibernate prepareStatementCount 를 기록하고, 측정 회차 중 1회의 SQL 목록을
 * org.hibernate.SQL·JdbcTemplate DEBUG 로그로 남김(테스트 안에서만 로거 레벨 변경, 끝나면 되돌림).
 *
 * <p>① 데모 사용자(Minh, 수정하지 않음) ② 목표·필수 확보액·체류 정보 행이 있는 perf- 사용자(행 수에 따라
 * SQL 이 늘어나는지 보려는 비교용, 끝나면 삭제).
 */
@Tag("perf")
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"})
@AutoConfigureMockMvc
class OnboardingSummaryPerfTest {

    private static final int WARMUP = 1;
    private static final int RUNS = 10;
    private static final Path REPORT = Path.of("build", "perf", "onb04-summary-sql.md");
    private static final String[] SQL_LOGGERS = {"org.hibernate.SQL", "org.springframework.jdbc.core.JdbcTemplate"};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void ONB04_요청당_SQL_수() throws Exception {
        PerfSupport.deletePerfUsers(jdbc);
        try {
            Long minhId = userRepository.findByExternalUserKey("demo-e9-nguyen").orElseThrow().getId();
            Long perfId = createPerfUserWithRows();

            StringBuilder md = new StringBuilder(PerfSupport.header(jdbc, "ONB-04 온보딩 최종 확인 요청당 SQL 수 기준선",
                    "① 데모 사용자 Minh(현재 DB 상태 그대로) " + describe(minhId) + " ② perf- 사용자 " + describe(perfId),
                    "MockMvc GET /api/v1/onboarding/summary 워밍업 " + WARMUP + "회 + " + RUNS + "회"));
            md.append(measure("① 데모 사용자 Minh", minhId));
            md.append(measure("② perf- 사용자(목표·필수 확보액·체류 정보 있음)", perfId));
            PerfSupport.write(REPORT, md.toString());
        } finally {
            PerfSupport.deletePerfUsers(jdbc);
        }
    }

    private String measure(String label, Long userId) throws Exception {
        String bearer = "Bearer " + jwtTokenProvider.issue(userId);
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        List<Long> micros = new ArrayList<>();
        List<Long> comSelects = new ArrayList<>();
        List<Long> prepared = new ArrayList<>();
        List<String> sqlOfOneRequest = List.of();

        for (int round = 0; round < WARMUP + RUNS; round++) {
            boolean capture = round == WARMUP; // 첫 측정 회차의 SQL 목록만 남김
            stats.clear();
            long before = PerfSupport.globalStatus(jdbc, "Com_select");
            // 측정용 SHOW 문이 SQL 목록에 섞이지 않게 기준값을 읽은 뒤 캡처 시작
            Map<String, Level> previousLevels = new LinkedHashMap<>();
            ListAppender<ILoggingEvent> appender = capture ? startCapture(previousLevels) : null;
            long started = System.nanoTime();
            try {
                mockMvc.perform(get("/api/v1/onboarding/summary").header(HttpHeaders.AUTHORIZATION, bearer))
                        .andExpect(status().isOk());
            } finally {
                if (capture) {
                    stopCapture(appender, previousLevels);
                }
            }
            long elapsed = (System.nanoTime() - started) / 1_000;
            long selects = PerfSupport.globalStatus(jdbc, "Com_select") - before;
            System.out.printf("[perf] ONB-04 %s round=%d%s %d us, Com_select %d, Hibernate prepareStatement %d%n",
                    label, round, round < WARMUP ? "(warmup)" : "", elapsed, selects, stats.getPrepareStatementCount());
            if (capture) {
                sqlOfOneRequest = appender.list.stream().map(this::sqlOf).filter(s -> s != null).toList();
            }
            if (round >= WARMUP) {
                micros.add(elapsed);
                comSelects.add(selects);
                prepared.add(stats.getPrepareStatementCount());
            }
        }

        Map<String, Integer> repeats = new LinkedHashMap<>();
        sqlOfOneRequest.forEach(s -> repeats.merge(s.replaceAll("\\s+", " ").strip(), 1, Integer::sum));
        StringBuilder md = new StringBuilder("## " + label + "\n\n")
                .append("| 응답 시간 중앙값 | 최댓값 | Com_select 차이(회차별) | Hibernate prepareStatementCount(회차별) |\n")
                .append("|---|---|---|---|\n")
                .append(String.format("| %.2f ms | %.2f ms | %s | %s |%n%n", PerfSupport.median(micros) / 1000.0,
                        PerfSupport.max(micros) / 1000.0, comSelects, prepared))
                .append("회차별 응답 시간(µs): ").append(micros).append("\n\n")
                .append("### 요청 1회 동안 실행된 SQL (로그 순서, ").append(sqlOfOneRequest.size()).append("개)\n\n");
        for (int i = 0; i < sqlOfOneRequest.size(); i++) {
            md.append(i + 1).append(". `").append(sqlOfOneRequest.get(i).replaceAll("\\s+", " ").strip())
                    .append("`\n");
        }
        md.append("\n같은 SQL 반복: ");
        List<String> repeated = repeats.entrySet().stream().filter(e -> e.getValue() > 1)
                .map(e -> e.getValue() + "회 `" + e.getKey() + "`").toList();
        md.append(repeated.isEmpty() ? "없음" : String.join(", ", repeated)).append("\n\n");
        return md.toString();
    }

    /** Minh 은 건드리지 않고, 비교용 perf- 사용자에만 목표 3·필수 확보액 6·체류 정보 1행을 넣음. */
    private Long createPerfUserWithRows() {
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES ('perf-onb04', 'PERF ONB04')");
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = 'perf-onb04'", Long.class);
        Long e9 = jdbc.queryForObject("SELECT id FROM visa_types WHERE code = 'E-9'", Long.class);
        LocalDate expires = LocalDate.now().plusMonths(10);
        jdbc.update("INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, nationality_code) "
                + "VALUES (?, ?, ?, 'VNM')", userId, e9, expires);
        jdbc.update("INSERT INTO financial_goals (user_id, goal_type, target_amount, target_date) VALUES "
                + "(?, 'LUMP_SUM', 6000000, ?), (?, 'REMITTANCE', NULL, NULL), (?, 'LIVING_COST', NULL, NULL)",
                userId, expires, userId, userId);
        jdbc.update("INSERT INTO monthly_commitments (user_id, commitment_type, label, amount) VALUES "
                + "(?, 'RENT', 'rent', 400000), (?, 'UTILITY', 'utility', 70000), (?, 'UTILITY', 'water', 50000), "
                + "(?, 'REMITTANCE', 'family', 500000), (?, 'REMITTANCE', 'self', 200000), "
                + "(?, 'VARIABLE_EXPENSE', 'living', 450000)", userId, userId, userId, userId, userId, userId);
        return userId;
    }

    private String describe(Long userId) {
        return String.format("(userId=%d, 목표 %d·필수 확보액 %d·체류 정보 %d행)", userId,
                count("financial_goals", userId), count("monthly_commitments", userId),
                count("visa_profiles", userId));
    }

    private long count(String table, Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
    }

    private ListAppender<ILoggingEvent> startCapture(Map<String, Level> previousLevels) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        for (String name : SQL_LOGGERS) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            previousLevels.put(name, logger.getLevel());
            logger.addAppender(appender);
            logger.setLevel(Level.DEBUG);
        }
        return appender;
    }

    private void stopCapture(ListAppender<ILoggingEvent> appender, Map<String, Level> previousLevels) {
        for (String name : SQL_LOGGERS) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            logger.setLevel(previousLevels.get(name)); // 원래 레벨로 되돌림
            logger.detachAppender(appender);
        }
    }

    /**
     * Hibernate(org.hibernate.SQL)는 메시지가 곧 SQL. JdbcTemplate 은 "Executing prepared SQL statement [..]" 줄에서
     * SQL 만 꺼내고 SQL 이 없는 다른 DEBUG 줄은 버림(null).
     */
    private String sqlOf(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (!event.getLoggerName().endsWith("JdbcTemplate")) {
            return "[Hibernate] " + message;
        }
        if (message.startsWith("Executing prepared SQL statement [") || message.startsWith("Executing SQL query [")) {
            return "[JdbcTemplate] " + message.substring(message.indexOf('[') + 1, message.lastIndexOf(']'));
        }
        return null;
    }
}
