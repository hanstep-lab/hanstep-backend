package com.hanstep.goal.dto;

import com.hanstep.common.dto.Money;
import com.hanstep.common.time.KstDates;
import com.hanstep.goal.domain.FinancialGoal;
import com.hanstep.goal.domain.GoalStatus;
import com.hanstep.goal.domain.GoalType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "금융 목표(financial_goals)")
public record GoalResponse(
        @Schema(description = "목표 ID", example = "1")
        Long id,

        @Schema(description = "목표 종류", example = "LUMP_SUM")
        GoalType goalType,

        @Schema(description = "목표 금액. LUMP_SUM(목돈 마련)만 입력, 나머지는 null",
                example = "{\"amount\": 6000000, \"currency\": \"KRW\"}")
        Money targetAmount,

        @Schema(description = "목표 시점(YYYY-MM-DD). 입력 안 했으면 null", example = "2027-10-04", nullable = true)
        LocalDate targetDate,

        @Schema(description = "상태. 목록 조회는 ACTIVE 만 반환", example = "ACTIVE")
        GoalStatus status,

        @Schema(description = "목표 달성에 필요한 월 저축액(계산값). 목표 금액 ÷ 목표일까지 남은 개월 수(꽉 찬 달, 최소 1), "
                + "원 단위 올림. 목표 금액·목표일이 없거나 목표일이 지났으면 null",
                example = "{\"amount\": 600000, \"currency\": \"KRW\"}", nullable = true)
        Money monthlyRequired
) {

    public static GoalResponse from(FinancialGoal goal, LocalDate today) {
        Money amount = goal.getTargetAmount() == null ? null
                : new Money(goal.getTargetAmount().longValue(), goal.getCurrency());
        Long required = amount == null ? null
                : KstDates.monthlyRequired(amount.amount(), today, goal.getTargetDate());
        Money monthlyRequired = required == null ? null : new Money(required, goal.getCurrency());
        return new GoalResponse(goal.getId(), goal.getGoalType(), amount, goal.getTargetDate(), goal.getStatus(),
                monthlyRequired);
    }
}
