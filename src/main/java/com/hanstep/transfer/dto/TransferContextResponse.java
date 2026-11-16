package com.hanstep.transfer.dto;

import com.hanstep.common.dto.Money;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "해외송금 화면(TR-001) 첫 조회. 송금 실행은 하지 않는다")
public record TransferContextResponse(
        @Schema(description = "선택된 받는 분(recipientId 가 없으면 어머니 우선)")
        TransferRecipientResponse recipient,

        @ArraySchema(schema = @Schema(implementation = TransferRecipientResponse.class),
                arraySchema = @Schema(description = "관계를 확인한 받는 분 전체"))
        List<TransferRecipientResponse> recipients,

        @Schema(description = "출금 계좌(급여 계좌, 없으면 입출금 계좌)")
        Account account,

        @Schema(description = "송금 목적(받는 분 관계로 정한 임시값)")
        Purpose purpose,

        @ArraySchema(schema = @Schema(example = "500000"),
                arraySchema = @Schema(description = "금액 빠른 선택(원, 임시값)", example = "[100000, 500000, 1000000]"))
        List<Long> quickAmounts,

        @Schema(description = "Mock 데이터 여부(받는 분 또는 계좌가 Mock)", example = "true")
        boolean isMock
) {

    @Schema(name = "TransferAccount", description = "출금 계좌(financial_accounts)")
    public record Account(
            @Schema(description = "상품명", example = "신한 주거래 우대통장", nullable = true)
            String name,

            @Schema(description = "마스킹된 계좌번호(저장값 그대로)", example = "110-***-**4821")
            String number,

            @Schema(description = "잔액(원 단위 버림)", example = "{\"amount\": 9924200, \"currency\": \"KRW\"}")
            Money balance
    ) {
    }

    @Schema(name = "TransferPurpose", description = "송금 목적")
    public record Purpose(
            @Schema(description = "목적 코드", example = "FAMILY_SUPPORT")
            String code,

            @Schema(description = "표시 문구", example = "가족 생활비 (Family support)")
            String label
    ) {
    }
}
