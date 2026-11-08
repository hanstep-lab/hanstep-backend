package com.hanstep.onboarding.domain;

/**
 * 온보딩 단계. 선언 순서 = 화면 진행 순서(ON-001 언어 → ON-002·003 등록증 → ON-003A·B 마이데이터
 * → ON-004 목표 → ON-005 목표별 질문 → ON-006 최종 확인). 순서를 바꾸면 currentStep 계산이 달라짐.
 */
public enum OnboardingStep {
    LANGUAGE,
    ID_DOCUMENT,
    MYDATA_CONSENT,
    GOALS,
    GOAL_DETAILS,
    SUMMARY_CONFIRM
}
