package com.hanstep.goal.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "금융 목표 목록(ACTIVE 만)")
public record GoalListResponse(
        @ArraySchema(arraySchema = @Schema(description = "복수 선택된 목표. 선택 안 했으면 빈 배열"))
        List<GoalResponse> goals
) {
}
