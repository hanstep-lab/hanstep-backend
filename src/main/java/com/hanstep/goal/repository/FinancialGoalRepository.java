package com.hanstep.goal.repository;

import com.hanstep.goal.domain.FinancialGoal;
import com.hanstep.goal.domain.GoalStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialGoalRepository extends JpaRepository<FinancialGoal, Long> {

    List<FinancialGoal> findAllByUserIdAndStatusOrderById(Long userId, GoalStatus status);
}
