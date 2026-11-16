package com.hanstep.transfer.dto;

import com.hanstep.common.dto.Money;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "송금 견적(조회만, 송금 실행 없음). 받을 외화는 1단위 버림, 역산한 보낼 원화는 원 단위 올림")
public record RemittanceQuoteResponse(
        @Schema(description = "받는 분 ID", example = "1")
        Long recipientId,

        @Schema(description = "보낼 원화", example = "{\"amount\": 500000, \"currency\": \"KRW\"}")
        Money sendAmount,

        @Schema(description = "받는 분이 받을 외화", example = "{\"amount\": 22401, \"currency\": \"PHP\"}")
        Money receiveAmount,

        AppliedRate appliedRate
) {
}
