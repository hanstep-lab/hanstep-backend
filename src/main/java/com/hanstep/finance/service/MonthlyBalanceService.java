package com.hanstep.finance.service;

import com.hanstep.commitment.domain.CommitmentType;
import com.hanstep.commitment.dto.CommitmentListResponse;
import com.hanstep.commitment.dto.CommitmentTotals;
import com.hanstep.commitment.service.CommitmentService;
import com.hanstep.common.dto.Money;
import com.hanstep.finance.domain.BalanceSource;
import com.hanstep.finance.dto.FinanceAnalysisResponse;
import com.hanstep.finance.dto.MonthlyBalanceResponse;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월 수지 요약(#151). ONB-04 balance, INT-06 balance, PRD-02 여유자금이 이 값을 같이 쓴다.
 * 월소득은 최신 COMPLETED 금융 분석(FIN-05). 고정생활비·송금·변동지출은 매달 나가는 돈(CMT-02)에 그 묶음 항목이 있으면
 * 입력 합계(금액 0 도 입력으로 봄), 없으면(건너뜀) FIN-05 값. 여유자금은 여기서 다시 계산한다(MANDATORY_SAVING 은 빼지 않음).
 * 분석이 없거나 쓸 금액이 하나라도 비면(꽉 찬 달 없음) null.
 */
@Service
@Transactional(readOnly = true)
public class MonthlyBalanceService {

    private final FinanceAnalysisService financeAnalysisService;
    private final CommitmentService commitmentService;

    public MonthlyBalanceService(FinanceAnalysisService financeAnalysisService, CommitmentService commitmentService) {
        this.financeAnalysisService = financeAnalysisService;
        this.commitmentService = commitmentService;
    }

    /** 분석·매달 나가는 돈을 읽어 계산한다. 없으면 null. */
    public MonthlyBalanceResponse balance(Long userId) {
        return balance(userId, commitmentService.getCommitments(userId));
    }

    /** 이미 읽은 매달 나가는 돈(CMT-01 과 같은 값)으로 계산한다. 같은 응답 안에서 다시 읽지 않으려고 둔다. */
    public MonthlyBalanceResponse balance(Long userId, CommitmentListResponse commitments) {
        return financeAnalysisService.findLatestCompleted(userId).map(a -> compute(a, commitments)).orElse(null);
    }

    static MonthlyBalanceResponse compute(FinanceAnalysisResponse a, CommitmentListResponse commitments) {
        Set<CommitmentType> entered = EnumSet.noneOf(CommitmentType.class);
        commitments.items().forEach(item -> entered.add(item.commitmentType()));
        CommitmentTotals totals = commitments.totals();

        boolean fixedEntered = entered.stream().anyMatch(CommitmentService.FIXED_LIVING::contains);
        boolean remittanceEntered = entered.contains(CommitmentType.REMITTANCE);
        boolean variableEntered = entered.contains(CommitmentType.VARIABLE_EXPENSE);
        Money income = a.avgMonthlyIncome();
        Money fixedLiving = fixedEntered ? totals.fixedLiving() : a.fixedLiving();
        Money remittance = remittanceEntered ? totals.remittance() : a.remittance();
        Money variable = variableEntered ? totals.variableExpense()
                : a.variableExpense() == null ? null : a.variableExpense().avg();
        if (income == null || fixedLiving == null || remittance == null || variable == null) {
            return null;
        }
        Money spare = Money.krw(income.amount() - fixedLiving.amount() - remittance.amount() - variable.amount());
        return new MonthlyBalanceResponse(income, fixedLiving, source(fixedEntered), remittance,
                source(remittanceEntered), variable, source(variableEntered), spare, a.isMock());
    }

    private static BalanceSource source(boolean entered) {
        return entered ? BalanceSource.USER_INPUT : BalanceSource.TRANSACTIONS;
    }
}
