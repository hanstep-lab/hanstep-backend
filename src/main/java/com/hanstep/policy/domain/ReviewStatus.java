package com.hanstep.policy.domain;

/** policy_documents.review_status. APPROVED 만 검색 대상. */
public enum ReviewStatus {
    DRAFT, APPROVED, NEEDS_REVIEW, RETIRED;

    /** 관리자 검토로 바꿀 수 있는 상태(#53). 같은 상태로는 바꾸지 않고, RETIRED 는 끝 상태다. */
    public boolean canMoveTo(ReviewStatus to) {
        return switch (this) {
            case DRAFT -> to == APPROVED || to == NEEDS_REVIEW || to == RETIRED;
            case NEEDS_REVIEW -> to == APPROVED || to == RETIRED;
            case APPROVED -> to == RETIRED;
            case RETIRED -> false;
        };
    }
}
