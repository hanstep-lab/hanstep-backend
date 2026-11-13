package com.hanstep.policy.dto;

import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.domain.ReviewStatus;
import java.time.LocalDate;

/**
 * AI 상태 대조 스냅샷의 문서 1건. AI 레포 app/rag/reconcile.py 의 SnapshotDocument 와 같은 필드이고, AI 가 모르는 필드를
 * 거부하므로(extra=forbid) 필드를 늘리지 않는다. searchable 은 AI-R1 과 같은 판정, chunkCount 가 null 이면 아직 적재 전.
 */
public record PolicyDocumentStateItem(
        Long policyDocumentId,
        String policyVersion,
        String contentHash,
        ReviewStatus reviewStatus,
        boolean searchable,
        Integer chunkCount
) {
    public static PolicyDocumentStateItem of(PolicyDocument doc, LocalDate today) {
        return new PolicyDocumentStateItem(doc.getId(), doc.getPolicyVersion(), doc.getContentHash(),
                doc.getReviewStatus(), doc.isSearchable(today), doc.getChunkCount());
    }
}
