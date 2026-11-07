package com.hanstep.consent.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "동의 현황. 한 번도 동의하지 않은 종류는 목록에 없음")
public record ConsentListResponse(
        @ArraySchema(arraySchema = @Schema(description = "종류별 최신 1건"))
        List<ConsentResponse> consents
) {
}
