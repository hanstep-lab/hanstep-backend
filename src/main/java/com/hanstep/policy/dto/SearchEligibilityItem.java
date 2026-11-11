package com.hanstep.policy.dto;

import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.domain.ReviewStatus;
import java.time.LocalDate;

/** effectiveFrom 이 null 이면 원문에 시행일 표기가 없다는 뜻(AI 가 "시행일 미확인"으로 표시). */
public record SearchEligibilityItem(
        Long policyDocumentId,
        String policyVersion,
        ReviewStatus reviewStatus,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean searchable
) {
    public static SearchEligibilityItem of(PolicyDocument doc, LocalDate today) {
        return new SearchEligibilityItem(doc.getId(), doc.getPolicyVersion(), doc.getReviewStatus(),
                doc.getEffectiveFrom(), doc.getEffectiveTo(), doc.isSearchable(today));
    }
}
