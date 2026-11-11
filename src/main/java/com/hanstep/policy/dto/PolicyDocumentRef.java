package com.hanstep.policy.dto;

import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.domain.ReviewStatus;

/** AI-R6 응답. AI 는 policyDocumentId 로 적재하고 policyVersion 을 INT-13 documentVersion 으로 보낸다. */
public record PolicyDocumentRef(
        Long policyDocumentId,
        String policyVersion,
        ReviewStatus reviewStatus
) {
    public static PolicyDocumentRef of(PolicyDocument doc) {
        return new PolicyDocumentRef(doc.getId(), doc.getPolicyVersion(), doc.getReviewStatus());
    }
}
