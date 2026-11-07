package com.hanstep.goal.service;

import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.goal.domain.FinancialGoal;
import com.hanstep.goal.domain.GoalStatus;
import com.hanstep.goal.domain.GoalType;
import com.hanstep.goal.dto.GoalListResponse;
import com.hanstep.goal.dto.GoalResponse;
import com.hanstep.goal.dto.GoalSaveRequest;
import com.hanstep.goal.repository.FinancialGoalRepository;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GOL-01 ~ 02. */
@Service
@Transactional(readOnly = true)
public class GoalService {

    private final FinancialGoalRepository goalRepository;

    public GoalService(FinancialGoalRepository goalRepository) {
        this.goalRepository = goalRepository;
    }

    /** GOL-01. ACTIVE 목표만. */
    public GoalListResponse getGoals(Long userId) {
        return new GoalListResponse(findActive(userId).stream().map(GoalResponse::from).toList());
    }

    /** GOL-02. 전체 교체: 요청에 있는 종류는 갱신·생성, 없는 기존 ACTIVE 목표는 CANCELLED(삭제 안 함). */
    @Transactional
    public GoalListResponse saveGoals(Long userId, GoalSaveRequest request) {
        validate(request.goals());

        Map<GoalType, FinancialGoal> active = new EnumMap<>(GoalType.class);
        for (FinancialGoal goal : findActive(userId)) {
            if (active.putIfAbsent(goal.getGoalType(), goal) != null) {
                goal.cancel(); // 같은 종류 ACTIVE 가 여러 개면 하나만 남긴다
            }
        }
        for (GoalSaveRequest.Item item : request.goals()) {
            FinancialGoal goal = active.remove(item.goalType());
            if (goal == null) {
                goal = goalRepository.save(new FinancialGoal(userId, item.goalType()));
            }
            Money amount = item.targetAmount();
            goal.update(amount == null ? null : BigDecimal.valueOf(amount.amount()),
                    amount == null ? Money.KRW : amount.currency(), item.targetDate());
        }
        active.values().forEach(FinancialGoal::cancel);

        return getGoals(userId);
    }

    private List<FinancialGoal> findActive(Long userId) {
        return goalRepository.findAllByUserIdAndStatusOrderById(userId, GoalStatus.ACTIVE);
    }

    /** goalType 중복, targetAmount ≤ 0 은 400. */
    private static void validate(List<GoalSaveRequest.Item> items) {
        Set<GoalType> seen = EnumSet.noneOf(GoalType.class);
        for (int i = 0; i < items.size(); i++) {
            GoalSaveRequest.Item item = items.get(i);
            if (!seen.add(item.goalType())) {
                throw invalid("goals[" + i + "].goalType", "duplicate");
            }
            if (item.targetAmount() != null && item.targetAmount().amount() <= 0) {
                throw invalid("goals[" + i + "].targetAmount.amount", "must be greater than 0");
            }
        }
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, null,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
