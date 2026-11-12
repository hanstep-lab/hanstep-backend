package com.hanstep.visa.controller;

import com.hanstep.common.config.OpenApiConfig;
import com.hanstep.common.error.ErrorResponse;
import com.hanstep.visa.dto.E74AssessmentResponse;
import com.hanstep.visa.dto.E74AssessmentToolRequest;
import com.hanstep.visa.service.E74AssessmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal · AI Context", description = "AI → Spring 컨텍스트 조회 (X-Internal-Token)")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN)
@RestController
@RequestMapping("/internal/v1/ai-context/users/{userId}")
public class E74AssessmentInternalController {

    private final E74AssessmentService e74AssessmentService;

    public E74AssessmentInternalController(E74AssessmentService e74AssessmentService) {
        this.e74AssessmentService = e74AssessmentService;
    }

    @Operation(summary = "채팅 도구용 E-7-4 평가 생성",
            description = "챗봇 도구 calculate_e74_score 가 부른다. 앱용 평가 생성과 같은 서비스로 계산·저장하고, "
                    + "응답은 AI 레포 app/visa/tools.py 의 E74AssessmentToolResponse 형식(assessmentId, reused, result).")
    @ApiResponse(responseCode = "201", description = "새로 계산해 저장")
    @ApiResponse(responseCode = "200", description = "같은 입력으로 이미 저장된 결과(reused=true)")
    @ApiResponse(responseCode = "400", description = "형식 오류(details.fields). asOfDate 누락, 보낸 scenario·legalDeclaration 을 "
            + "AI 가 거부한 경우(시나리오를 반영한 프로필이 걸린 경우 포함)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없는 사용자",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: 기준일에 쓸 수 있는 규칙 버전이 없음(승인 전 규칙 포함). "
            + "저장하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_DATA: 저장된 프로필로 계산할 수 없음. details.reason "
            + "NO_VISA_PROFILE(체류 프로필 없음)·PROFILE_REJECTED(AI 가 프로필을 거부, details.fields 에 필드 경로)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "AI_UPSTREAM_ERROR: 계산 서버 호출 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/e74-assessments")
    public ResponseEntity<E74AssessmentResponse> create(@PathVariable Long userId,
                                                        @Valid @RequestBody E74AssessmentToolRequest request) {
        E74AssessmentService.Result result = e74AssessmentService.assessUser(userId, request.asOfDate(),
                request.scenario(), request.legalDeclaration());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }
}
