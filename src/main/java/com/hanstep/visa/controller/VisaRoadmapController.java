package com.hanstep.visa.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.visa.dto.VisaRoadmapCreateRequest;
import com.hanstep.visa.dto.VisaRoadmapDetailResponse;
import com.hanstep.visa.dto.VisaRoadmapResponse;
import com.hanstep.visa.service.VisaRoadmapService;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Visa Roadmap", description = "E-7-4 준비 로드맵(사전진단 기반, 허가 아님). 단계·점수는 AI 규칙 엔진, 문장만 LLM, "
        + "저장·재사용은 Spring")
@RestController
@RequestMapping("/api/v1/visa/roadmaps")
public class VisaRoadmapController {

    private final VisaRoadmapService visaRoadmapService;

    public VisaRoadmapController(VisaRoadmapService visaRoadmapService) {
        this.visaRoadmapService = visaRoadmapService;
    }

    @Operation(summary = "비자 로드맵 생성",
            description = "MySQL 의 체류·근무·소득·증빙과 체류 만료일로 E-7-4 평가를 확보하고(같은 입력이면 재사용) "
                    + "AI 로드맵 API 를 불러 결과를 쌓는다. 감점·제외 6문항(legalDeclaration)은 저장하지 않고 이번 계산에만 쓴다. "
                    + "같은 평가·체류 만료일로 저장된 로드맵이 LLM 문장이면 AI 를 부르지 않고 200 으로 준다. "
                    + "템플릿 문장(text.source TEMPLATE)이면 쿨다운(hanstep.roadmap.llm-retry-cooldown, 기본 5분)마다 "
                    + "한 번 AI 를 다시 불러, LLM 문장이 오면 같은 행(roadmapId 그대로)을 바꾸고 201, 또 템플릿이면 "
                    + "저장된 것을 200 으로 준다. 쿨다운 안이면 AI 를 부르지 않는다. "
                    + "알려진 동작: LLM 이 꺼져 있으면 템플릿 로드맵 POST 때 쿨다운마다 AI 를 1회 부른다(엔진만, 빠름). "
                    + "LLM 장애 때는 쿨다운마다 1회 AI 한도(15초)까지 기다린 뒤 저장된 템플릿을 준다. "
                    + "쿨다운 기록은 Spring 메모리라 여러 대로 늘리면 DB 칼럼으로 옮겨야 한다.")
    @ApiResponse(responseCode = "201", description = "새로 만들어 저장, 또는 템플릿 문장 로드맵을 LLM 문장으로 바꿈"
            + "(reused=false, roadmapId 그대로)")
    @ApiResponse(responseCode = "200", description = "같은 평가·체류 만료일로 이미 저장된 로드맵(reused=true). "
            + "템플릿 문장이면 쿨다운 안이거나 다시 불러도 템플릿이었던 것")
    @ApiResponse(responseCode = "400", description = "형식 오류(details.fields). legalDeclaration 값 범위 등",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: 기준일에 쓸 수 있는 규칙 버전이 없음(details.reason "
            + "NO_APPLICABLE_RULE_SET·RULE_SET_NOT_REGISTERED·RULE_SET_NOT_APPROVED(승인 전 규칙)). 저장하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_DATA: 저장된 정보로 만들 수 없음. details.reason "
            + "NO_VISA_PROFILE(체류 프로필 없음)·NO_STAY_EXPIRY(체류 만료일 없음)·STAY_EXPIRED(기준일에 이미 만료)·"
            + "PROFILE_REJECTED(AI 가 프로필을 거부, details.fields 에 필드 경로)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "AI_UPSTREAM_ERROR: 계산·로드맵 서버 호출 실패 또는 저장할 수 없는 응답",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public ResponseEntity<VisaRoadmapResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Valid @RequestBody(required = false) VisaRoadmapCreateRequest request) {
        VisaRoadmapService.Result result = request == null
                ? visaRoadmapService.create(userId, null, null)
                : visaRoadmapService.create(userId, request.asOfDate(), request.legalDeclaration());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @Operation(summary = "최신 비자 로드맵", description = "저장된 결과를 그대로 준다(AI·LLM 재호출 없음).")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "저장된 로드맵이 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/latest")
    public VisaRoadmapDetailResponse latest(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return visaRoadmapService.latest(userId);
    }

    @Operation(summary = "비자 로드맵 1건", description = "본인 로드맵만. 저장된 결과를 그대로 준다(AI·LLM 재호출 없음).")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없거나 다른 사용자의 로드맵",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{roadmapId}")
    public VisaRoadmapDetailResponse get(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "visa_roadmaps.id", example = "1") @PathVariable Long roadmapId) {
        return visaRoadmapService.get(userId, roadmapId);
    }
}
