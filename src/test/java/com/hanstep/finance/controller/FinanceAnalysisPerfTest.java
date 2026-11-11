package com.hanstep.finance.controller;

import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.finance.client.AiFinanceClient;
import com.hanstep.mydata.domain.MydataScope;
import com.hanstep.mydata.service.MockIngestionService;
import com.hanstep.mydata.service.MockMydataReader;
import com.hanstep.perf.PerfSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * FIN-05 반복 요청 기준선(측정만). 기본 test 에서는 빠짐: ./gradlew perfTest
 *
 * <p>perf- 사용자 1명에 데모(Minh) Mock 원천을 MYD-02 방식으로 적재한 뒤, 로컬 AI 서버(AI_INTERNAL_API_URL)의 실제 INT-01 을
 * 호출한다(규칙 계산이라 LLM 호출 없음). 같은 요청(Idempotency-Key 없이)을 워밍업 {@value #WARMUP}회 + 측정 {@value #RUNS}회를
 * 한 묶음으로 {@value #BATCHES}묶음 보낸다(같은 JVM, 같은 사용자). 회차마다 FIN-05 전체 시간과 그 안의 INT-01 호출 시간
 * (AiFinanceClient 스파이를 감싼 래퍼에서 nanoTime)을 기록하고, 나머지(전체 − INT-01)는 DB 조회·저장 등이다.
 * 결과 재사용 전 기준선이라 요청마다 AI 호출 1번·행 1개가 늘어나는 것이 정상이다.
 */
@Tag("perf")
@SpringBootTest
@AutoConfigureMockMvc
class FinanceAnalysisPerfTest {

    private static final int WARMUP = 10;
    private static final int RUNS = 30;
    private static final int BATCHES = 3;
    private static final long EXPECTED_SPARE = 675_317;
    private static final Path REPORT = Path.of("build", "perf", "fin05-repeat.md");
    private static final Path RAW = Path.of("build", "perf", "fin05-repeat-raw.csv");
    private static final List<MydataScope> SCOPES =
            List.of(MydataScope.ACCOUNT_BALANCE, MydataScope.TRANSACTIONS, MydataScope.CARD_USAGE);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockIngestionService ingestionService;

    @Autowired
    private MockMydataReader reader;

    @MockitoSpyBean
    private AiFinanceClient aiFinanceClient;

    /** 회차 하나. 시간은 ns. */
    private record Sample(int batch, int round, boolean warmup, long totalNs, long aiNs) {

        long restNs() {
            return totalNs - aiNs;
        }
    }

    @Test
    void FIN05_반복_요청_기준선() throws Exception {
        PerfSupport.deletePerfUsers(jdbc);
        try {
            long[] user = PerfSupport.createUsers(jdbc, 1).get(0);
            long userId = user[0];
            ingestionService.ingest(userId, "perf-fin05", user[1], SCOPES, 12, PerfSupport.targets(jdbc, reader, 1).get(0));
            String bearer = "Bearer " + jwtTokenProvider.issue(userId);

            // 측정용 래퍼: 실제 INT-01 호출을 그대로 하고 걸린 시간만 더함
            AtomicLong aiNanos = new AtomicLong();
            Mockito.doAnswer(invocation -> {
                long started = System.nanoTime();
                try {
                    return invocation.callRealMethod();
                } finally {
                    aiNanos.addAndGet(System.nanoTime() - started);
                }
            }).when(aiFinanceClient).analyze(any());

            List<Sample> samples = new ArrayList<>();
            long rowsBefore = rows(userId);
            Mockito.clearInvocations(aiFinanceClient);
            JsonNode first = null;
            for (int batch = 1; batch <= BATCHES; batch++) {
                for (int round = 1; round <= WARMUP + RUNS; round++) {
                    aiNanos.set(0);
                    long started = System.nanoTime();
                    MockHttpServletResponse response = mockMvc.perform(post("/api/v1/finance/analyses")
                            .header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse();
                    long totalNs = System.nanoTime() - started;
                    if (response.getStatus() != 201) {
                        fail("FIN-05 returned " + response.getStatus() + " " + response.getContentAsString()
                                + " (AI 서버가 떠 있는지, AI_INTERNAL_API_URL·AI_INTERNAL_API_TOKEN 확인)");
                    }
                    if (first == null) {
                        first = MAPPER.readTree(response.getContentAsString());
                        checkSpare(first);
                    }
                    samples.add(new Sample(batch, round, round <= WARMUP, totalNs, aiNanos.get()));
                }
            }
            long aiCalls = Mockito.mockingDetails(aiFinanceClient).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("analyze")).count();
            long addedRows = rows(userId) - rowsBefore;
            long txnCount = jdbc.queryForObject("SELECT source_txn_count FROM financial_analyses WHERE user_id = ? "
                    + "ORDER BY id DESC LIMIT 1", Long.class, userId);

            StringBuilder table = new StringBuilder("| 묶음 | 항목 | 중앙값 | p95 | 최소 | 최대 |\n|---|---|---|---|---|---|\n");
            for (int batch = 1; batch <= BATCHES + 1; batch++) {
                int b = batch;
                List<Sample> measured = samples.stream()
                        .filter(s -> !s.warmup() && (b > BATCHES || s.batch() == b)).toList();
                String label = b > BATCHES ? "합산(" + measured.size() + "회)" : b + "묶음";
                table.append(row(label, "전체", measured, Sample::totalNs))
                        .append(row(label, "INT-01", measured, Sample::aiNs))
                        .append(row(label, "나머지", measured, Sample::restNs));
            }
            String md = PerfSupport.header(jdbc, "FIN-05 반복 요청 기준선(결과 재사용 전)",
                    "perf- 사용자 1명, 데모 Minh Mock(신한은행·신한카드) 적재, INT-01 에 보낸 거래 " + txnCount + "건, 기간 "
                            + first.path("periodFrom").asString() + " ~ " + first.path("periodTo").asString(),
                    "MockMvc POST /api/v1/finance/analyses(Idempotency-Key 없음) 워밍업 " + WARMUP + "회 + 측정 " + RUNS
                            + "회 × " + BATCHES + "묶음(같은 JVM·같은 사용자)",
                    new String[] {"AI 서버", "로컬 INT-01 실제 호출(LLM 없음)"},
                    new String[] {"비고", "11/11 1차 측정(워밍업 1회)은 초반 회차 편차로 다시 잼"})
                    + "## 결과\n\n"
                    + "워밍업 회차는 표 계산에서 뺌(원시값은 " + RAW.getFileName() + " 에 워밍업 표시와 함께 남김). "
                    + "나머지 = 전체 − INT-01(DB 조회·저장 등), 회차별로 뺀 뒤 통계를 냄. p95 는 nearest-rank.\n\n"
                    + table + "\n"
                    + "| INT-01 호출 수(워밍업 포함) | 늘어난 financial_analyses 행 |\n|---|---|\n"
                    + "| " + aiCalls + " | " + addedRows + " |\n\n"
                    + "첫 결과: spare " + first.path("spare").path("amount").asLong() + " (기대 " + EXPECTED_SPARE + ")\n";
            System.out.print(md);
            PerfSupport.write(REPORT, md);
            PerfSupport.write(RAW, csv(samples));
        } finally {
            PerfSupport.deletePerfUsers(jdbc);
        }
    }

    private static String row(String label, String item, List<Sample> samples, ToLongFunction<Sample> metric) {
        List<Long> values = samples.stream().map(s -> metric.applyAsLong(s)).toList();
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return String.format(Locale.ROOT, "| %s | %s | %.1f ms | %.1f ms | %.1f ms | %.1f ms |%n", label, item,
                ms(PerfSupport.median(values)), ms(p95(sorted)), ms(sorted.get(0)), ms(PerfSupport.max(values)));
    }

    private static String csv(List<Sample> samples) {
        StringBuilder out = new StringBuilder("batch,round,warmup,total_ms,int01_ms\n");
        for (Sample s : samples) {
            out.append(String.format(Locale.ROOT, "%d,%d,%b,%.3f,%.3f%n", s.batch(), s.round(), s.warmup(),
                    ms(s.totalNs()), ms(s.aiNs())));
        }
        return out.toString();
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    // 첫 결과의 spare 가 INT-01 고정값(675,317)과 다르면 금액을 찍고 실패
    private static void checkSpare(JsonNode result) {
        long spare = result.path("spare").path("amount").asLong(Long.MIN_VALUE);
        if (spare != EXPECTED_SPARE) {
            fail("spare " + spare + " != " + EXPECTED_SPARE + ": income=" + amount(result, "avgMonthlyIncome")
                    + ", fixed=" + amount(result, "fixedLiving") + ", remittance=" + amount(result, "remittance")
                    + ", variable=" + result.path("variableExpense").path("avg").path("amount").asString()
                    + ", warnings=" + result.path("dataQuality").path("warnings"));
        }
    }

    private static String amount(JsonNode result, String field) {
        return result.path(field).path("amount").asString();
    }

    // nearest-rank p95
    private static long p95(List<Long> sorted) {
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    private long rows(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM financial_analyses WHERE user_id = ?", Long.class, userId);
    }
}
