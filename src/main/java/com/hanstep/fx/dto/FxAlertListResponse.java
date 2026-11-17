package com.hanstep.fx.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "내 목표 환율 알림 목록(ACTIVE·TRIGGERED, 최신순)")
public record FxAlertListResponse(
        @ArraySchema(arraySchema = @Schema(description = "알림. 없으면 빈 배열"))
        List<FxAlertResponse> alerts
) {
}
