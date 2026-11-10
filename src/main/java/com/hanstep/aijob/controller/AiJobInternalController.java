package com.hanstep.aijob.controller;

import com.hanstep.aijob.dto.JobFailedRequest;
import com.hanstep.aijob.dto.JobStartedRequest;
import com.hanstep.aijob.service.AiJobStatusService;
import com.hanstep.common.config.OpenApiConfig;
import com.hanstep.common.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal · AI Job", description = "Worker → Spring 작업 상태 보고 (X-Internal-Token)")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN)
@RestController
@RequestMapping("/internal/v1/ai/jobs/{jobId}")
public class AiJobInternalController {

    private final AiJobStatusService aiJobStatusService;

    public AiJobInternalController(AiJobStatusService aiJobStatusService) {
        this.aiJobStatusService = aiJobStatusService;
    }

    @Operation(summary = "작업 처리 시작 (INT-08)",
            description = "PENDING·QUEUED → PROCESSING, started_at 기록. 이미 시작·종료된 작업이면 409.")
    @ApiResponse(responseCode = "204", description = "반영됨")
    @ApiResponse(responseCode = "400", description = "본문 형식 오류(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "작업 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "이미 PROCESSING·COMPLETED·FAILED (INVALID_STATE)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/started")
    public ResponseEntity<Void> started(@PathVariable Long jobId, @Valid @RequestBody JobStartedRequest request) {
        aiJobStatusService.markStarted(jobId, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "작업 실패 (INT-10)",
            description = "status=FAILED, error_code·error_message·completed_at 기록. 같은 내용 재전송은 204. "
                    + "retryable 은 기록만 한다(재시도 정책은 아직 없음).")
    @ApiResponse(responseCode = "204", description = "반영됨")
    @ApiResponse(responseCode = "400", description = "본문 형식 오류(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "작업 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "이미 COMPLETED 이거나 다른 오류로 FAILED (INVALID_STATE)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/failed")
    public ResponseEntity<Void> failed(@PathVariable Long jobId, @Valid @RequestBody JobFailedRequest request) {
        aiJobStatusService.markFailed(jobId, request);
        return ResponseEntity.noContent().build();
    }
}
