package com.hanstep.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "답변 근거")
public record ChatSource(
        @Schema(description = "POLICY_DOCUMENT·PRODUCT_DOCUMENT·FINANCIAL_ANALYSIS·E74_ASSESSMENT·VISA_ROADMAP",
                example = "FINANCIAL_ANALYSIS")
        String type,

        @Schema(description = "근거 행 ID. PRODUCT_DOCUMENT 는 id null, chunkRef=상품 id", example = "7", nullable = true)
        Long id,

        @Schema(description = "문서 청크 ID. PRODUCT_DOCUMENT 는 상품 id(예: doc-7b32d0e385aa)", nullable = true)
        String chunkRef,

        @Schema(description = "페이지·조항", nullable = true)
        String pageLabel
) {
}
