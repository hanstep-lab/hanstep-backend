package com.hanstep.visa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(description = "E-7-4 평가 생성 결과(사전진단이며 허가가 아님)")
public record E74AssessmentResponse(
        @Schema(description = "e74_assessments.id", example = "1")
        long assessmentId,

        @Schema(description = "같은 입력(requestKey)으로 이미 저장된 결과를 돌려줬으면 true(새 행 없음)", example = "false")
        boolean reused,

        @Schema(description = "AI 계산 응답 원본(e74_assessments.result_json): ruleVersion, status, score, potentialScore, "
                + "items, missingInputs, needsEvidence, improvements 등", implementation = Object.class)
        JsonNode result
) {
}
