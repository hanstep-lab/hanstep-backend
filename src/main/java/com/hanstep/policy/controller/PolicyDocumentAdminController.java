package com.hanstep.policy.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.policy.dto.PolicyDocumentReviewRequest;
import com.hanstep.policy.dto.PolicyDocumentReviewResponse;
import com.hanstep.policy.service.PolicyDocumentReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin · Policy", description = "관리자 정책 문서 검토(users.role = ADMIN 인 사용자의 JWT)")
@RestController
public class PolicyDocumentAdminController {

    private final PolicyDocumentReviewService policyDocumentReviewService;

    public PolicyDocumentAdminController(PolicyDocumentReviewService policyDocumentReviewService) {
        this.policyDocumentReviewService = policyDocumentReviewService;
    }

    @Operation(summary = "정책 문서 검토 상태 변경 (#53)",
            description = "바꿀 수 있는 상태: DRAFT → APPROVED·NEEDS_REVIEW·RETIRED, NEEDS_REVIEW → APPROVED·RETIRED, "
                    + "APPROVED → RETIRED. effectiveFrom·effectiveTo 를 보내면 시행 기간도 바꾼다(보내지 않으면 그대로). "
                    + "APPROVED 로 바꾸면 같은 문서(sourceType·externalId)의 이전 승인 버전을 같은 트랜잭션으로 정리한다(#42): "
                    + "새 버전 시행일이 더 늦으면 이전 버전 effectiveTo = 새 시행일, 시행일이 없거나 같으면 이전 버전 RETIRED. "
                    + "externalId 가 없는 문서는 묶지 않는다. 바뀐 행은 changed 에 담고 policy_document_reviews 에 이력을 남긴다. "
                    + "MongoDB 청크 상태는 바꾸지 않는다(AI 가 상태 목록 API 로 대조).")
    @ApiResponse(responseCode = "200", description = "바뀜. 적재 전 문서를 승인하면 warnings 에 NOT_INGESTED")
    @ApiResponse(responseCode = "400", description = "본문 형식 오류, effectiveTo 가 effectiveFrom 보다 늦지 않음(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: 관리자(ADMIN)가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "policy_documents 행 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: 바꿀 수 없는 상태(details.reason INVALID_TRANSITION) "
            + "또는 시행일이 더 늦은 버전이 이미 승인됨(NEWER_VERSION_APPROVED, details.policyDocumentId)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PatchMapping("/api/v1/admin/policy-documents/{policyDocumentId}/review")
    public PolicyDocumentReviewResponse review(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "policy_documents.id", example = "8") @PathVariable Long policyDocumentId,
            @Valid @RequestBody PolicyDocumentReviewRequest request) {
        return policyDocumentReviewService.review(userId, policyDocumentId, request);
    }
}
