package com.hanstep.commitment.service;

import com.hanstep.commitment.domain.CommitmentSource;
import com.hanstep.commitment.domain.CommitmentType;
import com.hanstep.commitment.domain.MonthlyCommitment;
import com.hanstep.commitment.dto.CommitmentListResponse;
import com.hanstep.commitment.dto.CommitmentResponse;
import com.hanstep.commitment.dto.CommitmentSaveRequest;
import com.hanstep.commitment.dto.CommitmentTotals;
import com.hanstep.commitment.repository.MonthlyCommitmentRepository;
import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CMT-01 ~ 02. */
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
            if (!Money.KRW.equals(items.get(i).amount().currency())) {
                throw invalid("items[" + i + "].amount.currency", "must be KRW");
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
