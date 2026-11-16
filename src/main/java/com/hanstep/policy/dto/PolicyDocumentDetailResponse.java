package com.hanstep.policy.dto;

import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.domain.PolicySourceType;
import com.hanstep.policy.domain.ReviewStatus;
import java.time.LocalDate;

/**
 * 정책 문서 단건(AI Worker 적재용). AI 는 objectKey 로 R2 원본을 읽고 contentHash 로 대조한 뒤,
 * 출처 메타를 청크에 넣고 policyVersion 을 INT-13 documentVersion 으로 보낸다. 개인정보는 없다.
 */
public record PolicyDocumentDetailResponse(
        Long policyDocumentId,
        PolicySourceType sourceType,
        String title,
        String issuingAgency,
        String sourceUrl,
        String externalId,
        String visaTypeCode,
        LocalDate publishedOn,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String policyVersion,
        String contentHash,
        String objectKey,
        ReviewStatus reviewStatus
) {
    public static PolicyDocumentDetailResponse of(PolicyDocument doc) {
        return new PolicyDocumentDetailResponse(doc.getId(), doc.getSourceType(), doc.getTitle(),
                doc.getIssuingAgency(), doc.getSourceUrl(), doc.getExternalId(), doc.getVisaTypeCode(),
                doc.getPublishedOn(), doc.getEffectiveFrom(), doc.getEffectiveTo(), doc.getPolicyVersion(),
                doc.getContentHash(), doc.getObjectKey(), doc.getReviewStatus());
    }
}
