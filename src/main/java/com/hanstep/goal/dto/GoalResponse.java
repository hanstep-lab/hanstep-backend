package com.hanstep.goal.dto;

import com.hanstep.common.dto.Money;
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
        GoalStatus status
) {

    public static GoalResponse from(FinancialGoal goal) {
        Money amount = goal.getTargetAmount() == null ? null
                : new Money(goal.getTargetAmount().longValue(), goal.getCurrency());
        return new GoalResponse(goal.getId(), goal.getGoalType(), amount, goal.getTargetDate(), goal.getStatus());
    }
}
