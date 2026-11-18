package com.hanstep.fx.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "아직 알리지 않은 목표 환율 도달(도달 시각 최신순)")
public record FxAlertNotificationListResponse(
        @ArraySchema(arraySchema = @Schema(description = "도달. 없으면 빈 배열"))
        List<FxAlertNotification> notifications
) {
}
