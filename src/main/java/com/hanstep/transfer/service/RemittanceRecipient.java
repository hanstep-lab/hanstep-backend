package com.hanstep.transfer.service;

/**
 * 저장된 받는 분 1행(remittance_recipients). 화면·내부 API 응답 모양으로 바꾸기 전 공용 값.
 *
 * @param accountLast4 account_no_masked 의 마지막 숫자 4자리(전체 계좌번호는 저장돼 있지 않음)
 */
public record RemittanceRecipient(long recipientId, String nameEn, String relationship, String countryCode,
                                  String currency, String bankName, String accountLast4, boolean mock) {
}
