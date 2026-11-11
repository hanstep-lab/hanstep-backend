package com.hanstep.finance.service;

import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.finance.client.AiFinanceClient;
import com.hanstep.finance.client.AiFinanceClient.AiCallException;
import com.hanstep.finance.client.AiFinanceClient.AnalyzeRequest;
import com.hanstep.finance.client.AiFinanceClient.AnalyzeResponse;
import com.hanstep.finance.client.AiFinanceClient.Transaction;
import com.hanstep.finance.domain.AnalysisStatus;
import com.hanstep.finance.domain.FinancialAnalysis;
import com.hanstep.finance.dto.FinanceAnalysisResponse;
import com.hanstep.finance.repository.FinancialAnalysisRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * FIN-05 금융 분석 실행, FIN-06 최신 결과. 금액은 INT-01(AI 서버의 규칙 계산, LLM 아님)이 계산하고 여기서는 옮겨 저장만 한다.
 * AI 응답을 기다리는 동안 DB 커넥션을 잡지 않도록 analyze 는 트랜잭션 없이 돌고, 저장만 짧게(saveAndFlush) 한다.
 */
@Service
public class FinanceAnalysisService {

    /** INT-01 계산 규칙 버전(임시값). AI 쪽 규칙이 바뀌면 같이 올린다. */
    public static final String ENGINE_VERSION = "int01-2026-11-11";

    static final int MAX_MONTHS = 12;
    static final int MAX_KEY_LENGTH = 100;

    private static final Logger log = LoggerFactory.getLogger(FinanceAnalysisService.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final FinancialAnalysisRepository analysisRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AiFinanceClient aiFinanceClient;

    public FinanceAnalysisService(FinancialAnalysisRepository analysisRepository, JdbcTemplate jdbcTemplate,
                                  AiFinanceClient aiFinanceClient) {
        this.analysisRepository = analysisRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.aiFinanceClient = aiFinanceClient;
    }

    /** created=false 면 같은 Idempotency-Key 재요청(200, AI 호출 없음). */
    public record Result(FinanceAnalysisResponse body, boolean created) {
    }

    /** FIN-05. 같은 Idempotency-Key 의 기존 행이 FAILED 면 409 INVALID_STATE(새 키로 다시 요청, MYD-02 와 같은 규칙). */
    public Result analyze(Long userId, String idempotencyKey) {
        String requestKey = requestKey(idempotencyKey);
        Optional<FinancialAnalysis> existing = analysisRepository.findByUserIdAndRequestKey(userId, requestKey);
        if (existing.isPresent()) {
            return new Result(replay(existing.get()), false);
        }

        BatchRow batch = latestBatch(userId).orElseThrow(() -> insufficient("NO_SUCCEEDED_BATCH"));
        if (batch.coverageFrom() == null || batch.coverageTo() == null) {
            throw insufficient("NO_COVERAGE");
        }
        AnalysisPeriod period = period(batch.coverageFrom(), batch.coverageTo());
        List<Transaction> transactions = transactions(userId, period);
        if (transactions.isEmpty()) {
            throw insufficient("NO_TRANSACTIONS");
        }

        FinancialAnalysis.Source source = new FinancialAnalysis.Source(userId, requestKey, batch.id(), period.from(),
                period.to(), ENGINE_VERSION, transactions.size(), fingerprint(period, transactions));
        AnalyzeResponse response;
        try {
            response = aiFinanceClient.analyze(new AnalyzeRequest(userId, period.from(), period.to(), transactions));
        } catch (AiCallException e) {
            log.warn("finance analysis failed: userId={} errorCode={}", userId, e.getErrorCode(), e);
            saveOrExisting(FinancialAnalysis.failed(source, e.getErrorCode(), now()));
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
        Saved saved = saveOrExisting(FinancialAnalysis.completed(source, amounts(response), quality(response), now()));
        if (!saved.created()) {
            return new Result(replay(saved.analysis()), false);
        }
        return new Result(toResponse(saved.analysis(), batch.mock()), true);
    }

    // 같은 키 재요청: COMPLETED 는 그대로, FAILED 는 409. isMock 은 그 분석에 쓴 배치 기준
    private FinanceAnalysisResponse replay(FinancialAnalysis existing) {
        if (existing.getStatus() == AnalysisStatus.FAILED) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "analysis with this Idempotency-Key failed",
                    Map.of("status", AnalysisStatus.FAILED.name()));
        }
        return toResponse(existing, batchMock(existing.getSourceBatchId()));
    }

    private boolean batchMock(Long batchId) {
        if (batchId == null) {
            return false;
        }
        return jdbcTemplate.query("SELECT is_mock FROM ingestion_batches WHERE id = ?",
                (rs, i) -> rs.getBoolean("is_mock"), batchId).stream().findFirst().orElse(false);
    }

    /** FIN-06. 최신 COMPLETED 1건, 없으면 404. */
    @Transactional(readOnly = true)
    public FinanceAnalysisResponse latest(Long userId) {
        return findLatestCompleted(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 최신 COMPLETED 분석(ONB-04 balance 용). FAILED 는 보지 않는다. SQL 1번. */
    @Transactional(readOnly = true)
    public Optional<FinanceAnalysisResponse> findLatestCompleted(Long userId) {
        List<Object[]> rows = analysisRepository.findLatestCompletedWithMock(userId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(toResponse((FinancialAnalysis) row[0], Boolean.TRUE.equals(row[1])));
    }

    /**
     * 수집 기간을 온전한 달력 월로 자르고(CMT-03 과 같은 규칙), 12개월이 넘으면 끝에서 12개월만.
     * 꽉 찬 달이 하나도 없으면 원래 수집 기간을 그대로 보내 INT-01 이 NO_FULL_MONTH 로 답하게 한다
     * (자른 기간은 시작이 끝보다 뒤라 INT-01 이 요청을 거부한다).
     */
    static AnalysisPeriod period(LocalDate coverageFrom, LocalDate coverageTo) {
        LocalDate from = KstDates.firstFullMonthStart(coverageFrom);
        LocalDate to = KstDates.lastFullMonthEnd(coverageTo);
        if (from.isAfter(to)) {
            return new AnalysisPeriod(coverageFrom, coverageTo);
        }
        LocalDate earliest = to.plusDays(1).minusMonths(MAX_MONTHS);
        return new AnalysisPeriod(from.isBefore(earliest) ? earliest : from, to);
    }

    record AnalysisPeriod(LocalDate from, LocalDate to) {
    }

    private record BatchRow(Long id, boolean mock, LocalDate coverageFrom, LocalDate coverageTo) {
    }

    private record Saved(FinancialAnalysis analysis, boolean created) {
    }

    private Optional<BatchRow> latestBatch(Long userId) {
        return jdbcTemplate.query("""
                        SELECT id, is_mock, coverage_from, coverage_to FROM ingestion_batches
                        WHERE user_id = ? AND status = 'SUCCEEDED' ORDER BY id DESC LIMIT 1""",
                (rs, i) -> new BatchRow(rs.getLong("id"), rs.getBoolean("is_mock"),
                        rs.getObject("coverage_from", LocalDate.class),
                        rs.getObject("coverage_to", LocalDate.class)),
                userId).stream().findFirst();
    }

    /** 기간 안 KRW 거래 전부(은행·카드 모든 계좌). 분류 값은 DB 저장값 그대로. 금액 0 이하는 빼고 개수만 로그. */
    private List<Transaction> transactions(Long userId, AnalysisPeriod period) {
        int[] skipped = {0};
        List<Transaction> rows = new ArrayList<>();
        jdbcTemplate.query("""
                        SELECT id, booked_date, direction, amount, category_code, transaction_kind
                        FROM financial_transactions
                        WHERE user_id = ? AND currency = 'KRW' AND booked_date BETWEEN ? AND ?
                        ORDER BY id""",
                rs -> {
                    BigDecimal amount = rs.getBigDecimal("amount");
                    if (amount.signum() <= 0) {
                        skipped[0]++;
                        return;
                    }
                    long id = rs.getLong("id");
                    rows.add(new Transaction(id, rs.getObject("booked_date", LocalDate.class),
                            rs.getString("direction"), wholeWon(id, amount), rs.getString("category_code"),
                            rs.getString("transaction_kind")));
                },
                userId, period.from(), period.to());
        if (skipped[0] > 0) {
            log.warn("finance analysis skipped {} transactions with amount <= 0: userId={}", skipped[0], userId);
        }
        return rows;
    }

    private static long wholeWon(long txnId, BigDecimal amount) {
        try {
            return amount.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("transaction " + txnId + " amount is not whole KRW", e);
        }
    }

    /** 기간 + 엔진 버전 + 보낸 거래(id 순)의 SHA-256. 저장만 하고 재사용 판단에는 아직 쓰지 않는다. */
    static String fingerprint(AnalysisPeriod period, List<Transaction> transactions) {
        StringBuilder text = new StringBuilder()
                .append(period.from()).append('|').append(period.to()).append('|').append(ENGINE_VERSION);
        transactions.stream()
                .sorted((a, b) -> Long.compare(a.id(), b.id()))
                .forEach(t -> text.append('\n').append(t.id()).append(',').append(t.bookedDate()).append(',')
                        .append(t.direction()).append(',').append(t.amount()).append(',')
                        .append(t.categoryCode()).append(',').append(t.transactionKind()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // 동시에 같은 Idempotency-Key 가 오면 UNIQUE 위반 → 먼저 들어간 행을 돌려준다
    private Saved saveOrExisting(FinancialAnalysis analysis) {
        try {
            return new Saved(analysisRepository.saveAndFlush(analysis), true);
        } catch (DataIntegrityViolationException e) {
            FinancialAnalysis first = analysisRepository
                    .findByUserIdAndRequestKey(analysis.getUserId(), analysis.getRequestKey())
                    .orElseThrow(() -> e);
            return new Saved(first, false);
        }
    }

    private static FinancialAnalysis.Amounts amounts(AnalyzeResponse r) {
        AiFinanceClient.VariableExpense variable = r.variableExpense();
        return new FinancialAnalysis.Amounts(decimal(r.avgMonthlyIncome()), decimal(r.fixedLiving()),
                decimal(r.remittance()), variable == null ? null : decimal(variable.avg()),
                variable == null ? null : decimal(variable.min()), variable == null ? null : decimal(variable.max()),
                decimal(r.spare()));
    }

    private static FinancialAnalysis.Quality quality(AnalyzeResponse r) {
        AiFinanceClient.DataQuality q = r.dataQuality();
        List<Map<String, Object>> excluded = r.excluded() == null ? List.of() : r.excluded().stream()
                .map(e -> Map.<String, Object>of("transactionId", e.transactionId(), "reason", e.reason()))
                .toList();
        return new FinancialAnalysis.Quality(q.observedMonthCount(), json(q.missingMonths()),
                json(q.partialMonths()), json(q.warnings()), json(excluded));
    }

    private static FinanceAnalysisResponse toResponse(FinancialAnalysis a, boolean mock) {
        FinanceAnalysisResponse.VariableExpense variable = a.getAvgMonthlyVariable() == null ? null
                : new FinanceAnalysisResponse.VariableExpense(money(a.getAvgMonthlyVariable()),
                money(a.getVariableMonthlyMin()), money(a.getVariableMonthlyMax()));
        return new FinanceAnalysisResponse(a.getId(), a.getPeriodFrom(), a.getPeriodTo(), a.getStatus(),
                money(a.getAvgMonthlyIncome()), money(a.getAvgMonthlyFixed()), money(a.getAvgMonthlyRemittance()),
                variable, money(a.getAvgMonthlySurplus()),
                new FinanceAnalysisResponse.DataQuality(a.getObservedMonthCount(), strings(a.getMissingMonthsJson()),
                        strings(a.getPartialMonthsJson()), strings(a.getWarningsJson())),
                a.getExcludedJson() == null ? 0 : MAPPER.readValue(a.getExcludedJson(), List.class).size(),
                mock, false, a.getCreatedAt().atOffset(ZoneOffset.ofHours(9)));
    }

    private static BigDecimal decimal(Money money) {
        return money == null ? null : BigDecimal.valueOf(money.amount());
    }

    private static Money money(BigDecimal amount) {
        return amount == null ? null : new Money(amount.longValueExact(), Money.KRW);
    }

    private static String json(Object value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    private static List<String> strings(String json) {
        return json == null ? List.of() : MAPPER.readValue(json, STRING_LIST);
    }

    private static String requestKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return UUID.randomUUID().toString();
        }
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("fields",
                    List.of(Map.of("field", "Idempotency-Key", "reason", "must be 1~100 characters"))));
        }
        return idempotencyKey;
    }

    private static BusinessException insufficient(String reason) {
        return new BusinessException(ErrorCode.INSUFFICIENT_DATA, Map.of("reason", reason));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
