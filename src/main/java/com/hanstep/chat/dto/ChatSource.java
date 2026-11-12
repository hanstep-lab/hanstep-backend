package com.hanstep.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "답변 근거")
public record ChatSource(
        @Schema(description = "POLICY_DOCUMENT·PRODUCT_DOCUMENT·FINANCIAL_ANALYSIS·E74_ASSESSMENT·VISA_ROADMAP",
                example = "FINANCIAL_ANALYSIS")
        String type,

        @Schema(description = "근거 행 ID", example = "7")
        Long id,

        @Schema(description = "문서 청크 ID", nullable = true)
        String chunkRef,

        @Schema(description = "페이지·조항", nullable = true)
        String pageLabel
) {
}
