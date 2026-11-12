package com.hanstep.policy.dto;

import java.util.List;

/** AI 상태 대조용 전체 목록. AI scripts/reconcile_policy_state.py 의 스냅샷 파일과 같은 모양이라 documents 외 필드를 넣지 않는다. */
public record PolicyDocumentStateResponse(List<PolicyDocumentStateItem> documents) {
}
