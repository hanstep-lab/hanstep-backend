package com.hanstep.visa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

@Schema(description = "저장된 E-7-4 평가 1건. 재계산하지 않고 저장된 결과를 그대로 준다")
public record E74AssessmentDetailResponse(
        @Schema(description = "e74_assessments.id", example = "1")
        long assessmentId,

        @Schema(description = "계산 시각(KST)", example = "2026-11-12T14:05:00+09:00")
        OffsetDateTime createdAt,

        @Schema(description = "AI 계산 응답 원본(e74_assessments.result_json)", implementation = Object.class)
        JsonNode result
) {
}
