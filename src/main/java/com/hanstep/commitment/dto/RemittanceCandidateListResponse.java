package com.hanstep.commitment.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "'이전 송금 이력을 분석했어요' 블록. 마이데이터 미연결이면 candidates 는 빈 배열")
public record RemittanceCandidateListResponse(
        @ArraySchema(arraySchema = @Schema(description = "최대 3개, rank 순"))
        List<RemittanceCandidateResponse> candidates,

        @Schema(description = "분석한 기간(개월)", example = "12")
        int analyzedMonths,

        @Schema(description = "Mock 거래내역 기반이면 true", example = "true")
        boolean isMock
) {
}
