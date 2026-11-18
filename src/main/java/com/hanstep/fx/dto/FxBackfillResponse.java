package com.hanstep.fx.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "환율 백필 결과. 다시 실행해도 같은 값은 새 행을 만들지 않는다(unchanged)")
public record FxBackfillResponse(
        @Schema(description = "시작일(포함)", example = "2025-11-17") LocalDate from,
        @Schema(description = "종료일(포함)", example = "2026-11-17") LocalDate to,
        @Schema(description = "걸린 시간(ms)", example = "118000") long elapsedMs,
        @Schema(description = "원천별 결과") List<FxCollectResult> sources
) {
}
