package com.hanstep.aicontext.controller;

import com.hanstep.aicontext.dto.AiFinanceContextResponse;
import com.hanstep.aicontext.service.AiContextService;
import com.hanstep.common.config.OpenApiConfig;
import com.hanstep.common.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal · AI Context", description = "AI → Spring 컨텍스트 조회 (X-Internal-Token)")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN)
@RestController
@RequestMapping("/internal/v1/ai-context/users/{userId}")
public class AiContextInternalController {

    private final AiContextService aiContextService;

    public AiContextInternalController(AiContextService aiContextService) {
        this.aiContextService = aiContextService;
    }

    @Operation(summary = "AI 용 금융 컨텍스트 (INT-06)",
            description = "챗봇 도구 get_financial_analysis 가 부른다. 최신 분석(FIN-06), 필수 확보액 합계(CMT-01 totals), "
                    + "금융 목표(GOL-01)를 기존 값 그대로 준다. 계좌·잔액·계좌번호·거래처·생년월일·등록번호는 없다.")
    @ApiResponse(responseCode = "200", description = "성공. 완료된 분석이 없으면 latestAnalysis 는 null")
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없는 사용자",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/finance")
    public AiFinanceContextResponse finance(@PathVariable Long userId) {
        return aiContextService.finance(userId);
    }
}
