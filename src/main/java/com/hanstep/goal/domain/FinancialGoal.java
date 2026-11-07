package com.hanstep.goal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** financial_goals 테이블. 전체 교체(GOL-02) 때 빠진 목표는 지우지 않고 CANCELLED. */
@Entity
@Table(name = "financial_goals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "goal_type", nullable = false,
            columnDefinition = "enum('LUMP_SUM','REMITTANCE','LIVING_COST','CREDIT','SETTLEMENT')")
    private GoalType goalType;

    @Column(name = "target_amount", precision = 18, scale = 2)
    private BigDecimal targetAmount;

    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "enum('ACTIVE','ACHIEVED','CANCELLED')")
    private GoalStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public FinancialGoal(Long userId, GoalType goalType) {
        this.userId = userId;
        this.goalType = goalType;
        this.currency = "KRW";
        this.status = GoalStatus.ACTIVE;
    }

    public void update(BigDecimal targetAmount, String currency, LocalDate targetDate) {
        this.targetAmount = targetAmount;
        this.currency = currency;
        this.targetDate = targetDate;
    }

    public void cancel() {
        this.status = GoalStatus.CANCELLED;
    }
}
