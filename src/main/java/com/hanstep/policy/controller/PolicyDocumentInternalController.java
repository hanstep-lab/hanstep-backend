package com.hanstep.policy.controller;

import com.hanstep.common.config.OpenApiConfig;
import com.hanstep.common.error.ErrorResponse;
import com.hanstep.policy.dto.LawDocumentRegisterRequest;
import com.hanstep.policy.dto.PolicyDocumentRef;
import com.hanstep.policy.dto.PolicyIngestedRequest;
import com.hanstep.policy.dto.SearchEligibilityResponse;
import com.hanstep.policy.service.LawDocumentRegisterService;
import com.hanstep.policy.service.PolicyDocumentInternalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal · Policy", description = "AI → Spring 정책 문서 내부 API (X-Internal-Token)")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN)
@RestController
public class PolicyDocumentInternalController {

    private final PolicyDocumentInternalService policyDocumentInternalService;
    private final LawDocumentRegisterService lawDocumentRegisterService;

    public PolicyDocumentInternalController(PolicyDocumentInternalService policyDocumentInternalService,
                                            LawDocumentRegisterService lawDocumentRegisterService) {
        this.policyDocumentInternalService = policyDocumentInternalService;
        this.lawDocumentRegisterService = lawDocumentRegisterService;
    }

    @Operation(summary = "정책 문서 검색 가능 여부 (AI-R1)",
            description = "AI 가 검색 결과를 MySQL 승인 상태와 대조할 때 검색 1회당 1번 부른다. "
                    + "searchable = APPROVED 이고 오늘(KST)이 시행 기간 안(effectiveTo 미포함). "
                    + "effectiveFrom 이 null(원문에 시행일 없음)이면 시작 조건은 건너뛴다. "
                    + "없는 ID 는 404 가 아니라 목록에서 빠진다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "400", description = "ids 가 비었거나 숫자가 아니거나 20개 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/internal/v1/ai-context/policy-documents/search-eligibility")
    public SearchEligibilityResponse searchEligibility(
            @Parameter(description = "쉼표로 구분한 policyDocumentId, 1~20개", example = "12,15,31")
            @RequestParam(required = false) String ids) {
        return policyDocumentInternalService.searchEligibility(ids);
    }

    @Operation(summary = "정책 문서 인덱싱 완료 (INT-13)",
            description = "AI 가 MongoDB 청크 저장·재조회 검증을 마친 뒤 보낸다. chunk_count·chroma_collection·"
                    + "embedding_model(embeddingModel@embeddingRevision)·ingested_at 을 기록하고 object_key 는 비어 있을 때만 채운다. "
                    + "review_status 는 바꾸지 않는다(인덱싱 완료는 승인이 아님). 같은 보고를 다시 보내도 204.")
    @ApiResponse(responseCode = "204", description = "반영됨")
    @ApiResponse(responseCode = "400", description = "본문 형식 오류(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "policy_documents 행 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "documentVersion·contentHash 가 MySQL 행과 다름(INVALID_STATE)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/internal/v1/ai/policies/{policyDocumentId}/ingested")
    public ResponseEntity<Void> recordIngested(@PathVariable Long policyDocumentId,
                                               @Valid @RequestBody PolicyIngestedRequest request) {
        policyDocumentInternalService.recordIngested(policyDocumentId, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "법제처 법령 문서 등록 (AI-R6)",
            description = "AI 가 법제처에서 새 법령 버전을 발견하면 적재 전에 부른다. 새 행은 항상 DRAFT. "
                    + "policyVersion = LAW-{externalId}-{yyyyMMdd}(시행일, 없으면 공포일, 둘 다 없으면 등록일 KST). "
                    + "같은 법령·날짜에 해시가 다른 행이 있으면 -{contentHash 앞 6자}를 붙인다. "
                    + "같은 contentHash 행이 이미 있으면 그 행을 200 으로 돌려준다.")
    @ApiResponse(responseCode = "201", description = "새로 등록")
    @ApiResponse(responseCode = "200", description = "같은 contentHash 행이 이미 있음")
    @ApiResponse(responseCode = "400",
            description = "본문 형식 오류, sourceType 이 LAW 가 아님, Idempotency-Key 누락 또는 contentHash 와 다름(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/internal/v1/ai-context/policy-documents")
    public ResponseEntity<PolicyDocumentRef> registerLaw(
            @Parameter(description = "contentHash 와 같은 값") @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody LawDocumentRegisterRequest request) {
        LawDocumentRegisterService.Result result = lawDocumentRegisterService.register(idempotencyKey, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.document());
    }
}
