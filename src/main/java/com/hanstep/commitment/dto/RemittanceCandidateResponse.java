package com.hanstep.commitment.dto;

import com.hanstep.common.dto.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "거래내역에서 찾은 정기 해외송금 후보 하나")
public record RemittanceCandidateResponse(
        @Schema(description = "순위(월평균 금액 큰 순, 1~3)", example = "1")
        int rank,

        @Schema(description = "받는 분 이름(거래내역 counterparty_name 표기)", example = "NGUYEN THI MAI")
        String counterpartyName,

        @Schema(description = "받는 은행(거래내역 counterparty_bank)", example = "Vietcombank", nullable = true)
        String counterpartyBank,

        @Schema(description = "등록된 받는 분(remittance_recipients) ID. 매칭 안 되면 null", example = "1",
                nullable = true)
        Long recipientId,

        @Schema(description = "월평균 송금액", example = "{\"amount\": 500000, \"currency\": \"KRW\"}")
        Money avgMonthlyAmount,

        @Schema(description = "송금한 달 수(분석 기간 중)", example = "12")
        int monthsSent,

        @Schema(description = "마지막 송금일(YYYY-MM-DD, KST)", example = "2026-10-10")
        LocalDate lastSentOn,

        @Schema(description = "마지막 송금 거래 ID. CMT-02 저장 시 detectedFromTxnId 로 보냄", example = "1187")
        Long lastTransactionId,

        @Schema(description = "받는 분 이름이 사용자 이름과 같으면 true. 화면의 '본인 명의 계좌' 표시용. "
                + "관계를 추정한 값이 아님(관계는 사용자 확인값만 RCP-01에 저장)", example = "false")
        boolean sameNameAsUser
) {
}
