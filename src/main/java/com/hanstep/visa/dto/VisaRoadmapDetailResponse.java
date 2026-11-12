package com.hanstep.visa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

@Schema(description = "저장된 비자 로드맵 1건. AI(LLM)를 다시 부르지 않고 저장된 결과를 그대로 준다")
public record VisaRoadmapDetailResponse(
        @Schema(description = "visa_roadmaps.id", example = "1")
        long roadmapId,

        @Schema(description = "생성 시각(KST)", example = "2026-11-12T14:05:00+09:00")
        OffsetDateTime createdAt,

        @Schema(description = "AI 로드맵 응답 원본(visa_roadmaps.result_json)", implementation = Object.class)
        JsonNode roadmap
) {
}
