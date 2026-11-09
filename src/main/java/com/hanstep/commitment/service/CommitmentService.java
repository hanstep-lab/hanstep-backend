package com.hanstep.commitment.service;

import com.hanstep.commitment.domain.CommitmentSource;
import com.hanstep.commitment.domain.CommitmentType;
import com.hanstep.commitment.domain.MonthlyCommitment;
import com.hanstep.commitment.dto.CommitmentListResponse;
import com.hanstep.commitment.dto.CommitmentResponse;
import com.hanstep.commitment.dto.CommitmentSaveRequest;
import com.hanstep.commitment.dto.CommitmentTotals;
import com.hanstep.commitment.dto.RemittanceCandidateListResponse;
import com.hanstep.commitment.dto.RemittanceCandidateResponse;
import com.hanstep.commitment.repository.MonthlyCommitmentRepository;
import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CMT-01 ~ 03. */
@Service
@Transactional(readOnly = true)
public class CommitmentService {

    /** 고정생활비 묶음(CommitmentTotals.fixedLiving). */
    private static final Set<CommitmentType> FIXED_LIVING = EnumSet.of(CommitmentType.RENT, CommitmentType.UTILITY,
            CommitmentType.TELECOM, CommitmentType.LIVING_MINIMUM, CommitmentType.OTHER);

    private final MonthlyCommitmentRepository commitmentRepository;
    private final JdbcTemplate jdbcTemplate;

    public CommitmentService(MonthlyCommitmentRepository commitmentRepository, JdbcTemplate jdbcTemplate) {
        this.commitmentRepository = commitmentRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** CMT-01. 활성 항목과 유형별 합계. */
    public CommitmentListResponse getCommitments(Long userId) {
        List<MonthlyCommitment> active = findActive(userId);
        return new CommitmentListResponse(active.stream().map(CommitmentResponse::from).toList(), totals(active));
    }

    /**
     * CMT-02. 전체 교체: 내용이 같은 기존 활성 항목은 그대로 두고, 나머지 요청 항목은 새로 만들고,
     * 요청에 없는 기존 활성 항목은 is_active=false(삭제 안 함). 같은 유형 여러 개 가능.
     */
    @Transactional
    public CommitmentListResponse saveCommitments(Long userId, CommitmentSaveRequest request) {
        List<CommitmentSaveRequest.Item> items = request.items();
        for (int i = 0; i < items.size(); i++) {
            CommitmentSaveRequest.Item item = items.get(i);
            if (!Money.KRW.equals(item.amount().currency())) {
                throw invalid("items[" + i + "].amount.currency", "must be KRW");
            }
            // source 가 null 이면 USER 로 본다
            boolean detected = item.source() == CommitmentSource.DETECTED_CONFIRMED;
            if (detected != (item.detectedFromTxnId() != null)) {
                throw invalid("items[" + i + "].detectedFromTxnId",
                        detected ? "required when source is DETECTED_CONFIRMED"
                                : "allowed only when source is DETECTED_CONFIRMED");
            }
        }
        for (CommitmentSaveRequest.Item item : items) {
            if (item.detectedFromTxnId() != null && !isOwnTransaction(userId, item.detectedFromTxnId())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
        }

        // 짝지어진 기존 행은 remaining 에서 빼므로 같은 요청 항목이 여러 개여도 행 하나에 하나씩만 짝지어진다
        List<MonthlyCommitment> remaining = new ArrayList<>(findActive(userId));
        for (CommitmentSaveRequest.Item item : items) {
            BigDecimal amount = BigDecimal.valueOf(item.amount().amount());
            String currency = item.amount().currency();
            CommitmentSource source = item.source() == null ? CommitmentSource.USER : item.source();
            MonthlyCommitment same = remaining.stream()
                    .filter(c -> c.sameAs(item.commitmentType(), item.label(), amount, currency, source,
                            item.detectedFromTxnId()))
                    .findFirst()
                    .orElse(null);
            if (same != null) {
                remaining.remove(same);
            } else {
                commitmentRepository.save(new MonthlyCommitment(userId, item.commitmentType(), item.label(), amount,
                        currency, source, item.detectedFromTxnId()));
            }
        }
        remaining.forEach(MonthlyCommitment::deactivate);

        return getCommitments(userId);
    }

    /**
     * CMT-03. 가장 최근 SUCCEEDED 배치의 수집 기간 안에서 2개월 이상 보낸 해외송금처를 월평균 큰 순으로 최대 3개.
     * 월평균 = 묶음 총액 ÷ 분석 개월 수(원 단위 반올림).
     */
    public RemittanceCandidateListResponse getRemittanceCandidates(Long userId) {
        List<BatchRow> batches = jdbcTemplate.query("""
                        SELECT source_type, coverage_from, coverage_to FROM ingestion_batches
                        WHERE user_id = ? AND status = 'SUCCEEDED' ORDER BY id DESC LIMIT 1""",
                (rs, i) -> new BatchRow("MOCK".equals(rs.getString("source_type")),
                        rs.getObject("coverage_from", LocalDate.class), rs.getObject("coverage_to", LocalDate.class)),
                userId);
        if (batches.isEmpty()) {
            return new RemittanceCandidateListResponse(List.of(), 0, false);
        }
        BatchRow batch = batches.get(0);
        LocalDate from = batch.coverageFrom();
        LocalDate to = batch.coverageTo();
        // 수집 기간이 없거나(원천 없는 기관만 연결) 한 달 미만이면 나눌 수 없으므로 빈 목록
        int analyzedMonths = from == null || to == null ? 0
                : Math.toIntExact(Period.between(from, to.plusDays(1)).toTotalMonths());
        if (analyzedMonths == 0) {
            return new RemittanceCandidateListResponse(List.of(), 0, batch.mock());
        }

        // 받는 분(이름·은행)별로 묶고, 묶음 안 마지막 거래는 날짜 → id 큰 순
        BigDecimal months = BigDecimal.valueOf(analyzedMonths);
        List<RemittanceCandidateResponse> groups = jdbcTemplate.query("""
                        WITH txns AS (
                            SELECT id, counterparty_name, counterparty_bank, amount, booked_date,
                                   ROW_NUMBER() OVER (PARTITION BY counterparty_name, counterparty_bank
                                                      ORDER BY booked_date DESC, id DESC) AS rn
                            FROM financial_transactions
                            WHERE user_id = ? AND category_code = 'REMITTANCE' AND direction = 'EXPENSE'
                              AND currency = 'KRW' AND counterparty_name IS NOT NULL
                              AND booked_date BETWEEN ? AND ?
                        ), grouped AS (
                            SELECT counterparty_name, counterparty_bank, SUM(amount) AS total,
                                   COUNT(DISTINCT DATE_FORMAT(booked_date, '%Y-%m')) AS months_sent,
                                   MAX(CASE WHEN rn = 1 THEN id END) AS last_id,
                                   MAX(CASE WHEN rn = 1 THEN booked_date END) AS last_date
                            FROM txns
                            GROUP BY counterparty_name, counterparty_bank
                            HAVING months_sent >= 2
                        )
                        SELECT g.*,
                               (SELECT MIN(r.id) FROM remittance_recipients r
                                WHERE r.user_id = ? AND r.is_active
                                  AND UPPER(r.name_en) = UPPER(g.counterparty_name)
                                  AND r.bank_name = g.counterparty_bank) AS recipient_id,
                               (SELECT u.display_name FROM users u WHERE u.id = ?) AS user_name
                        FROM grouped g""",
                (rs, i) -> {
                    String name = rs.getString("counterparty_name");
                    long avg = rs.getBigDecimal("total").divide(months, 0, RoundingMode.HALF_UP).longValueExact();
                    return new RemittanceCandidateResponse(0, name, rs.getString("counterparty_bank"),
                            rs.getObject("recipient_id", Long.class), Money.krw(avg), rs.getInt("months_sent"),
                            rs.getObject("last_date", LocalDate.class), rs.getLong("last_id"),
                            normalizeName(name).equals(normalizeName(rs.getString("user_name"))));
                },
                userId, from, to, userId, userId);

        List<RemittanceCandidateResponse> top = groups.stream()
                .sorted(Comparator.comparing((RemittanceCandidateResponse c) -> c.avgMonthlyAmount().amount())
                        .reversed()
                        .thenComparing(RemittanceCandidateResponse::counterpartyName))
                .limit(3)
                .toList();
        List<RemittanceCandidateResponse> ranked = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            RemittanceCandidateResponse c = top.get(i);
            ranked.add(new RemittanceCandidateResponse(i + 1, c.counterpartyName(), c.counterpartyBank(),
                    c.recipientId(), c.avgMonthlyAmount(), c.monthsSent(), c.lastSentOn(), c.lastTransactionId(),
                    c.sameNameAsUser()));
        }
        return new RemittanceCandidateListResponse(ranked, analyzedMonths, batch.mock());
    }

    private record BatchRow(boolean mock, LocalDate coverageFrom, LocalDate coverageTo) {
    }

    /** 대소문자·앞뒤 공백·연속 공백을 무시하고 비교하기 위한 이름. */
    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private List<MonthlyCommitment> findActive(Long userId) {
        return commitmentRepository.findAllByUserIdAndActiveTrueOrderById(userId);
    }

    /** 거래 엔티티가 아직 없어 count 쿼리로 본인 거래인지 확인한다. */
    private boolean isOwnTransaction(Long userId, Long txnId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM financial_transactions WHERE id = ? AND user_id = ?",
                Integer.class, txnId, userId);
        return count != null && count > 0;
    }

    /** MANDATORY_SAVING 은 따로 합산하고 deductibleTotal 에는 넣지 않는다. */
    private static CommitmentTotals totals(List<MonthlyCommitment> items) {
        long fixedLiving = 0;
        long remittance = 0;
        long variableExpense = 0;
        long mandatorySaving = 0;
        for (MonthlyCommitment item : items) {
            long amount = item.getAmount().longValue();
            CommitmentType type = item.getCommitmentType();
            if (FIXED_LIVING.contains(type)) {
                fixedLiving += amount;
            } else if (type == CommitmentType.REMITTANCE) {
                remittance += amount;
            } else if (type == CommitmentType.VARIABLE_EXPENSE) {
                variableExpense += amount;
            } else if (type == CommitmentType.MANDATORY_SAVING) {
                mandatorySaving += amount;
            }
        }
        return new CommitmentTotals(Money.krw(fixedLiving), Money.krw(remittance), Money.krw(variableExpense),
                Money.krw(mandatorySaving), Money.krw(fixedLiving + remittance + variableExpense));
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, null,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
