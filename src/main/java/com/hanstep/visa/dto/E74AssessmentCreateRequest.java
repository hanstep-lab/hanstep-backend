package com.hanstep.visa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.time.LocalDate;
import tools.jackson.databind.JsonNode;

@Schema(description = "E-7-4 평가 생성(앱). 프로필(체류·근무·소득·증빙)은 MySQL 에서 읽는다. 본문은 생략해도 된다")
public record E74AssessmentCreateRequest(
        @Schema(description = "계산 기준일(YYYY-MM-DD). 없으면 오늘(KST)", example = "2026-11-12", nullable = true)
        LocalDate asOfDate,

        @Schema(description = "가정 시나리오(개선 방안을 했다고 치고 계산). AI 계산 API 의 scenario 형식"
                + "(asOfDate, addEvidences, incomeRecords). 일반 계산은 null",
                implementation = Object.class, nullable = true,
                example = "{\"addEvidences\": [{\"evidenceType\": \"TOPIK\", \"levelValue\": \"4\"}]}")
        JsonNode scenario,

        @Valid
        LegalDeclaration legalDeclaration
) {
}
