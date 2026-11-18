package com.hanstep.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 데모 계정 초기화 결과(삭제 건수). */
@Schema(description = "데모 계정 초기화 결과")
public record DemoResetResponse(
        @Schema(description = "지운 등록증 문서 수(documents)", example = "1")
        int deletedDocuments,
        @Schema(description = "문서와 함께 지운 OCR 항목 수(document_fields)", example = "6")
        int deletedDocumentFields,
        @Schema(description = "지운 OCR 작업 수(ai_jobs, job_type=OCR)", example = "1")
        int deletedOcrJobs,
        @Schema(description = "지운 온보딩 단계 수(onboarding_progress)", example = "6")
        int deletedOnboardingSteps,
        @Schema(description = "지운 R2 원본 수(확정 전 문서만 원본이 남아 있음). DB 커밋 뒤에 지운다", example = "1")
        int deletedOriginals,
        @Schema(description = "지우지 못한 R2 원본 수. 실패해도 DB 는 이미 지워졌고 응답은 200", example = "0")
        int failedOriginals
) {
}
