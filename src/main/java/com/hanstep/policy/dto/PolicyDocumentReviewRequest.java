package com.hanstep.policy.dto;

import com.hanstep.policy.domain.ReviewStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 관리자 검토 상태 변경(#53). 날짜를 보내지 않으면(null) 지금 값을 그대로 둔다(날짜를 지우는 기능은 없음). */
public record PolicyDocumentReviewRequest(
        @Schema(description = "새 검토 상태", example = "APPROVED")
        @NotNull
        ReviewStatus reviewStatus,

        @Schema(description = "시행일(YYYY-MM-DD). 없으면 지금 값 유지", example = "2026-12-01", nullable = true)
        LocalDate effectiveFrom,

        @Schema(description = "종료일(미포함). 없으면 지금 값 유지", nullable = true)
        LocalDate effectiveTo,

        @Schema(description = "검토 메모(500자 이내, 이력에 남음)", example = "2026 선발계획 개선본 확인", nullable = true)
        @Size(max = 500)
        String note
) {
}
