package com.hanstep.user.domain;

/**
 * 화면·답변 언어. DB 값이 소문자(en, ko, vi, zh)라 상수도 소문자로 둠(@Enumerated(STRING)은 이름 그대로 저장).
 */
public enum Language {
    en, ko, vi, zh
}
