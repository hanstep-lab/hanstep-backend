package com.hanstep.visa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(description = "비자 로드맵 생성 결과(사전진단 기반 준비 단계이며 허가가 아님)")
public record VisaRoadmapResponse(
        @Schema(description = "visa_roadmaps.id", example = "1")
        long roadmapId,

        @Schema(description = "같은 평가·체류 만료일로 이미 저장된 로드맵을 돌려줬으면 true(새 행 없음, AI 호출 없음)",
                example = "false")
        boolean reused,

        @Schema(description = "AI 로드맵 응답 원본(visa_roadmaps.result_json): goalVisaCode, ruleVersion, ruleStatus, "
                + "assessmentRequestKey, stay, score, steps, projectedScore, missingInputs, warnings, text 등",
                implementation = Object.class)
        JsonNode roadmap
) {
}
