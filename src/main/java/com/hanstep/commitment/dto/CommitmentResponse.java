package com.hanstep.commitment.dto;

import com.hanstep.commitment.domain.CommitmentSource;
import com.hanstep.commitment.domain.CommitmentType;
import com.hanstep.commitment.domain.MonthlyCommitment;
import com.hanstep.common.dto.Money;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "매달 꼭 나가는 돈 한 항목(monthly_commitments)")
public record CommitmentResponse(
        @Schema(description = "항목 ID", example = "1")
        Long id,

        @Schema(description = "항목 유형. 같은 유형 여러 행 가능", example = "RENT")
        CommitmentType commitmentType,

        @Schema(description = "사용자 표시명", example = "월세", nullable = true)
        String label,

        @Schema(description = "월 금액", example = "{\"amount\": 400000, \"currency\": \"KRW\"}")
        Money amount,

        @Schema(description = "USER=직접 입력, DETECTED_CONFIRMED=송금 이력(CMT-03)에서 골라 확인", example = "USER")
        CommitmentSource source,

        @Schema(description = "근거 거래 ID(DETECTED_CONFIRMED 일 때)", example = "1187", nullable = true)
        Long detectedFromTxnId
) {

    public static CommitmentResponse from(MonthlyCommitment commitment) {
        return new CommitmentResponse(commitment.getId(), commitment.getCommitmentType(), commitment.getLabel(),
                new Money(commitment.getAmount().longValue(), commitment.getCurrency()), commitment.getSource(),
                commitment.getDetectedFromTxnId());
    }
}
