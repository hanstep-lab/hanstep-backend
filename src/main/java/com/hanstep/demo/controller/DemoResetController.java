package com.hanstep.demo.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.demo.dto.DemoResetResponse;
import com.hanstep.demo.service.DemoResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Demo", description = "테스트·시연 리허설용. DEMO_RESET_ENABLED=true 일 때만 열린다")
@RestController
@RequestMapping("/api/v1/demo")
public class DemoResetController {

    private final DemoResetService demoResetService;

    public DemoResetController(DemoResetService demoResetService) {
        this.demoResetService = demoResetService;
    }

    @Operation(summary = "데모 계정 초기화",
            description = "본문 없음. 요청한 사용자 본인의 온보딩 진행(onboarding_progress)과 등록증 문서(documents·"
                    + "document_fields)·OCR 작업(ai_jobs)·E-7-4 평가(e74_assessments)·비자 로드맵(visa_roadmaps)을 "
                    + "지우고, ACTIVE 목표 환율 알림을 CANCELLED 로 바꾼다(같은 조건으로 다시 만들 수 있음). "
                    + "Reyes(demo-e9-ph-reyes)는 visa_profiles 의 체류자격·만료일·국적·생년월일·증빙 상태와 서비스 언어"
                    + "(users.preferred_language, en)를 데모 시드 값으로 되돌린다.모두 한 트랜잭션이고, 커밋 뒤 확정 전 문서의 R2 원본을 지운다. "
                    + "초기화 뒤 ONB-01 은 NOT_STARTED. 입국일·이전 체류 개월, 다른 데모 계정의 visa_profiles, "
                    + "목표·마이데이터·금융 분석은 그대로 둔다.")
    @ApiResponse(responseCode = "200", description = "초기화(삭제·변경 건수). R2 원본 삭제가 실패해도 200(failedOriginals)")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: 데모 계정(external_user_key 가 demo- 로 시작)이 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 초기화 기능이 꺼져 있음(DEMO_RESET_ENABLED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/reset")
    public DemoResetResponse reset(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return demoResetService.reset(userId);
    }
}
