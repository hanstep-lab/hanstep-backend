package com.hanstep.policy.dto;

import com.hanstep.policy.domain.ReviewStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 검토 상태 변경 결과. changed 는 이번 문서가 먼저, 이어서 #42 로 정리된 이전 버전(AI 가 MongoDB 와 대조할 대상).
 * warnings: NOT_INGESTED(적재 전에 승인함. 청크가 생긴 뒤 AI 대조로 검색에 들어감).
 */
public record PolicyDocumentReviewResponse(List<Change> changed, List<String> warnings) {

    /** reason: REVIEW(관리자가 직접 바꿈), SUPERSEDED(새 버전 승인으로 정리). searchable 은 오늘(KST) 기준. */
    public record Change(
            Long policyDocumentId,
            String policyVersion,
            ReviewStatus previousStatus,
            ReviewStatus reviewStatus,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean searchable,
            String reason
    ) {
    }
}
