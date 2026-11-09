package com.hanstep.commitment.dto;

import com.hanstep.commitment.domain.CommitmentSource;
import com.hanstep.commitment.domain.CommitmentType;
import com.hanstep.common.dto.Money;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "필수 확보액 일괄 저장(전체 교체). 요청에 없는 기존 항목은 is_active=false. "
        + "ON-005 의 고정지출과 변동지출(VARIABLE_EXPENSE)을 한 번에 보낸다")
public record CommitmentSaveRequest(
        @ArraySchema(arraySchema = @Schema(description = "저장할 항목 전체"))
        @NotNull
        @Size(max = 30)
        List<@Valid @NotNull Item> items
) {

    @Schema(name = "CommitmentSaveItem", description = "저장할 항목 하나")
    public record Item(
            @Schema(description = "항목 유형", example = "RENT")
            @NotNull
            CommitmentType commitmentType,

            @Schema(description = "사용자 표시명", example = "월세", nullable = true)
            @Size(max = 100)
            String label,

            @Schema(description = "월 금액", example = "{\"amount\": 400000, \"currency\": \"KRW\"}")
            @NotNull
            @Valid
            Money amount,

            @Schema(description = "기본 USER. 송금 이력(CMT-03)에서 고른 항목은 DETECTED_CONFIRMED + detectedFromTxnId",
                    example = "USER", nullable = true)
            CommitmentSource source,

            @Schema(description = "근거 거래 ID(CMT-03 의 lastTransactionId)", example = "1187", nullable = true)
            Long detectedFromTxnId
    ) {
    }
}
