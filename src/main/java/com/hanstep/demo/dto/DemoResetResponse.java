package com.hanstep.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 데모 계정 초기화 결과(삭제·변경 건수). */
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
        int failedOriginals,
        @Schema(description = "CANCELLED 로 바꾼 ACTIVE 목표 환율 알림 수(fx_alerts, 행은 남김). 같은 조건으로 다시 만들 수 있다",
                example = "1")
        int alertsCancelled,
        @Schema(description = "visa_profiles 를 데모 시드 값으로 되돌렸는지. Reyes(demo-e9-ph-reyes)만 true, "
                + "다른 데모 계정이거나 프로필이 없으면 false", example = "true")
        boolean profileRestored,
        @Schema(description = "지운 E-7-4 평가 수(e74_assessments, 항목은 CASCADE)", example = "3")
        int e74AssessmentsDeleted,
        @Schema(description = "지운 비자 로드맵 수(visa_roadmaps, 단계는 CASCADE)", example = "1")
        int roadmapsDeleted,
        @Schema(description = "서비스 언어(users.preferred_language)를 데모 시드 값(en)으로 되돌렸는지. "
                + "Reyes(demo-e9-ph-reyes)만 true", example = "true")
        boolean languageRestored,
        @Schema(description = "CANCELLED 로 바꾼 ACTIVE 금융 목표 수(financial_goals, 행은 남김). "
                + "온보딩 목표 선택 화면이 체크 없이 시작한다", example = "2")
        int goalsCancelled,
        @Schema(description = "비활성(is_active=false)으로 바꾼 매달 나가는 돈 항목 수(monthly_commitments, 행은 남김). "
                + "온보딩 맞춤 질문이 빈 값으로 시작한다", example = "3")
        int commitmentsDeactivated
) {
}
