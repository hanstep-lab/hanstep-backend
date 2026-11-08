package com.hanstep.commitment.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "필수 확보액 목록(활성 항목만) + 합계")
public record CommitmentListResponse(
        @ArraySchema(arraySchema = @Schema(description = "활성 항목. 없으면 빈 배열"))
        List<CommitmentResponse> items,

        CommitmentTotals totals
) {
}
