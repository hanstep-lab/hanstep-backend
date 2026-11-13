package com.hanstep.visa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.time.LocalDate;

@Schema(description = "비자 로드맵 생성(앱). 프로필(체류·근무·소득·증빙)과 체류 만료일은 MySQL 에서 읽는다. 본문은 생략해도 된다")
public record VisaRoadmapCreateRequest(
        @Schema(description = "계산 기준일(YYYY-MM-DD). 없으면 오늘(KST)", example = "2026-11-12", nullable = true)
        LocalDate asOfDate,

        @Valid
        LegalDeclaration legalDeclaration
) {
}
