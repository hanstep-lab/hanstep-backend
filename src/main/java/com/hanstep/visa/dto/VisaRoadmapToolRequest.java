package com.hanstep.visa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 채팅 도구 get_visa_roadmap 용. AI 레포 app/visa/tools.py 의 RoadmapToolRequest(asOfDate) 형식.
 * legalDeclaration 은 AI 계약에 아직 없지만 선택 필드로 미리 받는다(없으면 roadmap.missingInputs 에 6문항이 나온다).
 */
@Schema(description = "채팅 도구용 비자 로드맵 생성. 프로필과 체류 만료일은 MySQL 에서 읽는다")
public record VisaRoadmapToolRequest(
        @Schema(description = "계산 기준일(YYYY-MM-DD). 채팅 쪽이 정해서 보낸다", example = "2026-11-12")
        @NotNull
        LocalDate asOfDate,

        @Valid
        LegalDeclaration legalDeclaration
) {
}
