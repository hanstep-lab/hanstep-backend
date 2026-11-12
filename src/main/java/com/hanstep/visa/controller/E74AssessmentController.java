package com.hanstep.visa.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.visa.dto.E74AssessmentCreateRequest;
import com.hanstep.visa.dto.E74AssessmentDetailResponse;
import com.hanstep.visa.dto.E74AssessmentResponse;
import com.hanstep.visa.service.E74AssessmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Visa E-7-4", description = "E-7-4 전환 점수 사전진단(허가 아님). 계산은 AI 규칙 엔진, 저장·재사용은 Spring")
@RestController
@RequestMapping("/api/v1/visa/e74/assessments")
public class E74AssessmentController {

    private final E74AssessmentService e74AssessmentService;

    public E74AssessmentController(E74AssessmentService e74AssessmentService) {
        this.e74AssessmentService = e74AssessmentService;
    }

    @Operation(summary = "E-7-4 평가 생성",
            description = "MySQL 의 체류·근무·소득·증빙으로 입력 스냅샷을 만들어 AI 계산 API 를 부르고 결과를 쌓는다. "
                    + "감점·제외 6문항(legalDeclaration)은 저장하지 않고 이번 계산에만 쓴다. "
                    + "같은 입력(requestKey)이면 새로 저장하지 않고 저장된 결과를 200 으로 준다.")
    @ApiResponse(responseCode = "201", description = "새로 계산해 저장")
    @ApiResponse(responseCode = "200", description = "같은 입력으로 이미 저장된 결과(reused=true)")
    @ApiResponse(responseCode = "400", description = "형식 오류(details.fields). 보낸 scenario·legalDeclaration 을 AI 가 "
            + "거부한 경우도 필드 경로를 담아 400",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: 기준일에 맞는 규칙 버전이 없음(details.reason "
            + "NO_APPLICABLE_RULE_SET·RULE_SET_NOT_REGISTERED). 저장하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_DATA: 저장된 프로필로 계산할 수 없음. details.reason "
            + "NO_VISA_PROFILE(체류 프로필 없음)·PROFILE_REJECTED(AI 가 프로필을 거부, details.fields 에 필드 경로)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "AI_UPSTREAM_ERROR: 계산 서버 호출 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public ResponseEntity<E74AssessmentResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Valid @RequestBody(required = false) E74AssessmentCreateRequest request) {
        E74AssessmentService.Result result = request == null
                ? e74AssessmentService.assess(userId, null, null, null)
                : e74AssessmentService.assess(userId, request.asOfDate(), request.scenario(), request.legalDeclaration());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @Operation(summary = "최신 E-7-4 평가", description = "저장된 결과를 그대로 준다(재계산 없음).")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "저장된 평가가 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/latest")
    public E74AssessmentDetailResponse latest(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "true 면 가정 시나리오 계산 중 최신, false 면 일반 계산 중 최신", example = "false")
            @RequestParam(defaultValue = "false") boolean scenario) {
        return e74AssessmentService.latest(userId, scenario);
    }

    @Operation(summary = "E-7-4 평가 1건", description = "본인 평가만. 저장된 결과를 그대로 준다(재계산 없음).")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없거나 다른 사용자의 평가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{assessmentId}")
    public E74AssessmentDetailResponse get(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "e74_assessments.id", example = "1") @PathVariable Long assessmentId) {
        return e74AssessmentService.get(userId, assessmentId);
    }
}
