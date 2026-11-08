package com.hanstep.consent.domain;

/** 동의 종류(user_consents.consent_type). MYDATA=개인신용정보 전송요구·수집이용, MYDATA_TERMS=마이데이터 서비스 이용약관, CREDIT_EVALUATION=금융 진단(모의) 활용(선택). */
public enum ConsentType {
    TERMS, PRIVACY, ID_DOCUMENT, MYDATA, MYDATA_TERMS, AI_CHAT, CREDIT_EVALUATION, MARKETING
}
