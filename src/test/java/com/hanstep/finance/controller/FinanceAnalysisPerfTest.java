package com.hanstep.finance.controller;

import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.finance.client.AiFinanceClient;
import com.hanstep.mydata.domain.MydataScope;
import com.hanstep.mydata.service.MockIngestionService;
import com.hanstep.mydata.service.MockMydataReader;
import com.hanstep.perf.PerfSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * 호출한다(규칙 계산이라 LLM 호출 없음). 같은 요청(Idempotency-Key 없이)을 워밍업 1회 + 30회 보내 FIN-05 전체 시간,
 * INT-01 호출 수(AiFinanceClient 스파이), 늘어난 financial_analyses 행 수를 기록한다. 결과 재사용 전 기준선이라
 * 요청마다 AI 호출 1번·행 1개가 늘어나는 것이 정상이다.
 */
@Tag("perf")
@SpringBootTest
@AutoConfigureMockMvc
class FinanceAnalysisPerfTest {

    private static final int WARMUP = 1;
    private static final int RUNS = 30;
    private static final long EXPECTED_SPARE = 675_317;
    private static final Path REPORT = Path.of("build", "perf", "fin05-repeat.md");
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

    @Test
    void FIN05_반복_요청_기준선() throws Exception {
        PerfSupport.deletePerfUsers(jdbc);
        try {
            long[] user = PerfSupport.createUsers(jdbc, 1).get(0);
            long userId = user[0];
            ingestionService.ingest(userId, "perf-fin05", user[1], SCOPES, 12, PerfSupport.targets(jdbc, reader, 1).get(0));
            String bearer = "Bearer " + jwtTokenProvider.issue(userId);

            List<Long> micros = new ArrayList<>();
            long rowsBefore = rows(userId);
            Mockito.clearInvocations(aiFinanceClient);
            JsonNode first = null;
            for (int round = 0; round < WARMUP + RUNS; round++) {
                long started = System.nanoTime();
                MockHttpServletResponse response = mockMvc.perform(post("/api/v1/finance/analyses")
                        .header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse();
                long elapsed = (System.nanoTime() - started) / 1_000;
                if (response.getStatus() != 201) {
                    fail("FIN-05 returned " + response.getStatus() + " " + response.getContentAsString()
                            + " (AI 서버가 떠 있는지, AI_INTERNAL_API_URL·AI_INTERNAL_API_TOKEN 확인)");
                }
                if (first == null) {
                    first = MAPPER.readTree(response.getContentAsString());
                    checkSpare(first);
                }
                System.out.printf("[perf] FIN-05 round=%d%s %d us%n", round, round < WARMUP ? "(warmup)" : "", elapsed);
                if (round >= WARMUP) {
                    micros.add(elapsed);
                }
            }
            long aiCalls = Mockito.mockingDetails(aiFinanceClient).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("analyze")).count();
            long addedRows = rows(userId) - rowsBefore;
            long txnCount = jdbc.queryForObject("SELECT source_txn_count FROM financial_analyses WHERE user_id = ? "
                    + "ORDER BY id DESC LIMIT 1", Long.class, userId);

            List<Long> sorted = new ArrayList<>(micros);
            Collections.sort(sorted);
            String table = String.format("| %.1f ms | %.1f ms | %.1f ms | %.1f ms | %d | %d |%n",
                    PerfSupport.median(micros) / 1000.0, p95(sorted) / 1000.0, sorted.get(0) / 1000.0,
                    PerfSupport.max(micros) / 1000.0, aiCalls, addedRows);
            String md = PerfSupport.header(jdbc, "FIN-05 반복 요청 기준선(결과 재사용 전)",
                    "perf- 사용자 1명, 데모 Minh Mock(신한은행·신한카드) 적재, INT-01 에 보낸 거래 " + txnCount + "건, 기간 "
                            + first.path("periodFrom").asString() + " ~ " + first.path("periodTo").asString(),
                    "MockMvc POST /api/v1/finance/analyses(Idempotency-Key 없음) 워밍업 " + WARMUP + "회 + " + RUNS + "회",
                    new String[] {"AI 서버", "로컬 INT-01 실제 호출(LLM 없음)"})
                    + "## 결과\n\n"
                    + "| 중앙값 | p95 | 최소 | 최대 | INT-01 호출 수(워밍업 포함) | 늘어난 financial_analyses 행 |\n"
                    + "|---|---|---|---|---|---|\n" + table + "\n"
                    + "첫 결과: spare " + first.path("spare").path("amount").asLong() + " (기대 " + EXPECTED_SPARE + ")\n\n"
                    + "회차별 FIN-05 시간(µs): " + micros + "\n";
            System.out.print(md);
            PerfSupport.write(REPORT, md);
        } finally {
            PerfSupport.deletePerfUsers(jdbc);
        }
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
