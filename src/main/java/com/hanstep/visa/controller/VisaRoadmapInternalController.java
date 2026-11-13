package com.hanstep.visa.controller;

import com.hanstep.common.config.OpenApiConfig;
import com.hanstep.common.error.ErrorResponse;
import com.hanstep.visa.dto.VisaRoadmapResponse;
import com.hanstep.visa.dto.VisaRoadmapToolRequest;
import com.hanstep.visa.service.VisaRoadmapService;
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
public class VisaRoadmapInternalController {

    private final VisaRoadmapService visaRoadmapService;

    public VisaRoadmapInternalController(VisaRoadmapService visaRoadmapService) {
        this.visaRoadmapService = visaRoadmapService;
    }

    @Operation(summary = "채팅 도구용 비자 로드맵 생성",
            description = "챗봇 도구 get_visa_roadmap 이 부른다. 앱용 로드맵 생성과 같은 서비스로 만들고 저장하며, 응답은 "
                    + "AI 레포 app/visa/tools.py 의 RoadmapToolResponse 형식(roadmapId, roadmap. reused 는 AI 가 무시).")
    @ApiResponse(responseCode = "201", description = "새로 만들어 저장")
    @ApiResponse(responseCode = "200", description = "같은 평가·체류 만료일로 이미 저장된 로드맵(reused=true)")
    @ApiResponse(responseCode = "400", description = "형식 오류(details.fields). asOfDate 누락, legalDeclaration 값 범위 등",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없는 사용자",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: 기준일에 쓸 수 있는 규칙 버전이 없음(승인 전 규칙 포함). "
            + "저장하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_DATA: 저장된 정보로 만들 수 없음. details.reason "
            + "NO_VISA_PROFILE·NO_STAY_EXPIRY·STAY_EXPIRED·PROFILE_REJECTED",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "AI_UPSTREAM_ERROR: 계산·로드맵 서버 호출 실패 또는 저장할 수 없는 응답",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/visa-roadmaps")
    public ResponseEntity<VisaRoadmapResponse> create(@PathVariable Long userId,
                                                      @Valid @RequestBody VisaRoadmapToolRequest request) {
        VisaRoadmapService.Result result = visaRoadmapService.createForUser(userId, request.asOfDate(),
                request.legalDeclaration());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }
}
