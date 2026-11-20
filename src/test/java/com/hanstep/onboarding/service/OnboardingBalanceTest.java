package com.hanstep.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.commitment.domain.CommitmentSource;
import com.hanstep.commitment.domain.CommitmentType;
import com.hanstep.commitment.dto.CommitmentListResponse;
import com.hanstep.commitment.dto.CommitmentResponse;
import com.hanstep.commitment.dto.CommitmentTotals;
import com.hanstep.common.dto.Money;
import com.hanstep.finance.domain.AnalysisStatus;
import com.hanstep.finance.dto.FinanceAnalysisResponse;
import com.hanstep.onboarding.dto.OnboardingSummaryResponse.Balance;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ONB-04 월 수지 요약(#149). 고정생활비·송금·변동지출은 직접 입력한 묶음이 있으면 입력 합계, 없으면 FIN-05 거래 평균.
 * 월소득은 FIN-05. 여유자금은 다시 계산한다. 금액은 QA 제보 값(고정 125만·변동 100만 입력, 거래 평균 고정 52만).
 */
class OnboardingBalanceTest {

    /** FIN-05 거래 평균: 소득 235만, 고정 52만, 송금 70만, 변동 454,683원. */
    private static final FinanceAnalysisResponse ANALYSIS = analysis(2_350_000L, 520_000L, 700_000L, 454_683L);

    @Test
    void 입력이_없으면_FIN05_값과_거래_기준_여유자금() {
        Balance balance = OnboardingService.balance(ANALYSIS, commitments());

        assertThat(balance.fixedLiving().amount()).isEqualTo(520_000L);
        assertThat(balance.remittance().amount()).isEqualTo(700_000L);
        assertThat(balance.variableExpense().amount()).isEqualTo(454_683L);
        assertThat(balance.variableExpenseSource()).isEqualTo("TRANSACTIONS");
        assertThat(balance.spare().amount()).isEqualTo(2_350_000L - 520_000L - 700_000L - 454_683L);
        assertThat(balance.isMock()).isTrue();
    }

    @Test
    void 고정과_변동을_입력하면_입력_합계를_쓰고_여유자금을_다시_계산한다() {
        Balance balance = OnboardingService.balance(ANALYSIS, commitments(
                item(CommitmentType.RENT, 900_000L),
                item(CommitmentType.UTILITY, 350_000L),
                item(CommitmentType.VARIABLE_EXPENSE, 1_000_000L)));

        assertThat(balance.avgMonthlyIncome().amount()).isEqualTo(2_350_000L);
        assertThat(balance.fixedLiving().amount()).isEqualTo(1_250_000L);
        // 송금은 입력하지 않아 거래 평균
        assertThat(balance.remittance().amount()).isEqualTo(700_000L);
        assertThat(balance.variableExpense().amount()).isEqualTo(1_000_000L);
        assertThat(balance.variableExpenseSource()).isEqualTo("USER_INPUT");
        // 음수 가능
        assertThat(balance.spare().amount()).isEqualTo(2_350_000L - 1_250_000L - 700_000L - 1_000_000L);
    }

    @Test
    void 송금만_입력하면_송금만_바꾸고_의무_저축은_빼지_않는다() {
        Balance balance = OnboardingService.balance(ANALYSIS, commitments(
                item(CommitmentType.REMITTANCE, 500_000L),
                item(CommitmentType.MANDATORY_SAVING, 300_000L)));

        assertThat(balance.fixedLiving().amount()).isEqualTo(520_000L);
        assertThat(balance.remittance().amount()).isEqualTo(500_000L);
        assertThat(balance.variableExpenseSource()).isEqualTo("TRANSACTIONS");
        assertThat(balance.spare().amount()).isEqualTo(2_350_000L - 520_000L - 500_000L - 454_683L);
    }

    @Test
    void 금액이_0인_입력도_입력으로_본다() {
        Balance balance = OnboardingService.balance(ANALYSIS, commitments(item(CommitmentType.VARIABLE_EXPENSE, 0L)));

        assertThat(balance.variableExpense().amount()).isZero();
        assertThat(balance.variableExpenseSource()).isEqualTo("USER_INPUT");
    }

    @Test
    void 월소득이_없으면_입력이_있어도_null() {
        FinanceAnalysisResponse noFullMonth = analysis(null, null, null, null);

        assertThat(OnboardingService.balance(noFullMonth, commitments(
                item(CommitmentType.RENT, 900_000L),
                item(CommitmentType.REMITTANCE, 500_000L),
                item(CommitmentType.VARIABLE_EXPENSE, 1_000_000L)))).isNull();
    }

    @Test
    void 입력하지_않은_묶음의_거래_값이_없으면_null() {
        FinanceAnalysisResponse noFixed = analysis(2_350_000L, null, 700_000L, 454_683L);

        assertThat(OnboardingService.balance(noFixed, commitments())).isNull();
        assertThat(OnboardingService.balance(noFixed, commitments(item(CommitmentType.RENT, 900_000L)))).isNotNull();
    }

    private static FinanceAnalysisResponse analysis(Long income, Long fixed, Long remittance, Long variable) {
        Money variableAvg = money(variable);
        return new FinanceAnalysisResponse(1L, null, null, AnalysisStatus.COMPLETED, money(income), money(fixed),
                money(remittance), variable == null ? null
                : new FinanceAnalysisResponse.VariableExpense(variableAvg, variableAvg, variableAvg),
                null, null, 0, true, false, null);
    }

    private static Money money(Long amount) {
        return amount == null ? null : Money.krw(amount);
    }

    private static CommitmentResponse item(CommitmentType type, long amount) {
        return new CommitmentResponse(null, type, null, Money.krw(amount), CommitmentSource.USER, null);
    }

    /** CommitmentService.totals 와 같은 묶음 규칙으로 합계를 만든다. */
    private static CommitmentListResponse commitments(CommitmentResponse... items) {
        long fixed = 0;
        long remittance = 0;
        long variable = 0;
        long saving = 0;
        for (CommitmentResponse item : items) {
            long amount = item.amount().amount();
            switch (item.commitmentType()) {
                case REMITTANCE -> remittance += amount;
                case VARIABLE_EXPENSE -> variable += amount;
                case MANDATORY_SAVING -> saving += amount;
                default -> fixed += amount;
            }
        }
        return new CommitmentListResponse(List.of(items), new CommitmentTotals(Money.krw(fixed),
                Money.krw(remittance), Money.krw(variable), Money.krw(saving),
                Money.krw(fixed + remittance + variable)));
    }
}
