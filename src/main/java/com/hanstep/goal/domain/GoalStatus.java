package com.hanstep.goal.domain;

/** 목표 상태(financial_goals.status). PUT 에서 빠진 목표는 CANCELLED. */
public enum GoalStatus {
    ACTIVE, ACHIEVED, CANCELLED
}
