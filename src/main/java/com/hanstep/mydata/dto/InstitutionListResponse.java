package com.hanstep.mydata.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "마이데이터 연결 가능 기관 목록")
public record InstitutionListResponse(
        @ArraySchema(arraySchema = @Schema(description = "MVP: 신한은행·신한카드"))
        List<InstitutionResponse> institutions
) {
}
