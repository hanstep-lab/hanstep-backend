package com.hanstep.finance.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.finance.dto.FinanceAnalysisResponse;
import com.hanstep.finance.service.FinanceAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Finance", description = "금융 분석(모의 진단, Mock 거래 기준)")
@RestController
@RequestMapping("/api/v1/finance/analyses")
public class FinanceAnalysisController {

    private final FinanceAnalysisService financeAnalysisService;

    public FinanceAnalysisController(FinanceAnalysisService financeAnalysisService) {
        this.financeAnalysisService = financeAnalysisService;
    }

    @Operation(summary = "금융 분석 실행 (FIN-05)",
            description = "요청 본문 없음. 가장 최근 적재(마이데이터 Mock)의 수집 기간을 온전한 달력 월로 자르고(최대 12개월) "
                    + "그 기간의 KRW 거래로 월평균 소득·고정지출·송금·변동지출·여유자금을 계산한다. 결과는 새 행으로 쌓인다. "
                    + "꽉 찬 달이 없으면 201 이지만 금액은 모두 null(warnings 에 NO_FULL_MONTH). "
                    + "spare 는 음수일 수 있다(지출이 소득보다 많음).")
    @ApiResponse(responseCode = "201", description = "새로 계산")
    @ApiResponse(responseCode = "200", description = "같은 Idempotency-Key 재요청: 처음 결과를 그대로 반환(다시 계산하지 않음)")
    @ApiResponse(responseCode = "400", description = "Idempotency-Key 형식 오류(1~100자, details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: 같은 Idempotency-Key 요청이 실패했음(새 키로 다시 요청)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_DATA: 적재된 데이터 없음 또는 기간 안 거래 0건(details.reason)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "AI_UPSTREAM_ERROR: 분석 서버 호출 실패(실패 기록은 남김)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public ResponseEntity<FinanceAnalysisResponse> analyze(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "중복 실행 방지 키(선택, 1~100자, UUID 권장)",
                    example = "8d0b6a5e-2f7c-4e19-b3a4-6c1e9f02d7b8")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        FinanceAnalysisService.Result result = financeAnalysisService.analyze(userId, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @Operation(summary = "최신 금융 분석 결과 (FIN-06)",
            description = "가장 최근 COMPLETED 결과 1건. 실패(FAILED) 결과는 건너뛴다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "완료된 분석이 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/latest")
    public FinanceAnalysisResponse latest(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return financeAnalysisService.latest(userId);
    }
}
