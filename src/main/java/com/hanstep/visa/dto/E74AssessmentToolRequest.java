package com.hanstep.visa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import tools.jackson.databind.JsonNode;

/** 채팅 도구 calculate_e74_score 용. AI 레포 app/visa/tools.py 의 E74AssessmentToolRequest 와 같은 형식. */
@Schema(description = "채팅 도구용 E-7-4 평가 생성. 프로필은 MySQL 에서 읽는다")
public record E74AssessmentToolRequest(
        @Schema(description = "계산 기준일(YYYY-MM-DD). 채팅 쪽이 정해서 보낸다", example = "2026-11-12")
        @NotNull
        LocalDate asOfDate,

        @Schema(description = "가정 시나리오. AI 계산 API 의 scenario 형식. 일반 계산은 null",
                implementation = Object.class, nullable = true)
        JsonNode scenario,

        @Valid
        LegalDeclaration legalDeclaration
) {
}
