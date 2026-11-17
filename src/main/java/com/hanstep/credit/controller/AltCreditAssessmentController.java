package com.hanstep.credit.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.credit.dto.AltCreditAssessmentResponse;
import com.hanstep.credit.service.AltCreditAssessmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Credit (Mock)", description = "대안신용 모의 사전진단. 모의 사전진단이며 실제 대출 승인이나 신용점수가 아닙니다")
@RestController
@RequestMapping("/api/v1/credit")
public class AltCreditAssessmentController {

    private final AltCreditAssessmentService altCreditAssessmentService;

    public AltCreditAssessmentController(AltCreditAssessmentService altCreditAssessmentService) {
        this.altCreditAssessmentService = altCreditAssessmentService;
    }

    @Operation(summary = "대안신용 모의 사전진단",
            description = "모의 사전진단이며 실제 대출 승인이나 신용점수가 아닙니다. "
                    + "체류 프로필(누적 체류·만료까지 개월·근로 가능 여부), Mock 거래·계좌(월소득·연속 급여 개월·신한 급여 계좌·"
                    + "청약·송금 비율·송금한 달), 청구 납부 내역(최근 6개월 통신비·건보·공과금 연체가 있었던 달 수)을 모아 "
                    + "AI 계산(모델 V9)을 부르고, 응답에 보낸 입력(inputs)과 Mock·임시값 입력 이름(mockFields)을 붙여 준다. "
                    + "저장하지 않는다(부를 때마다 다시 계산). "
                    + "decision: ELIGIBLE(모의 기준상 대상·조건 충족, 대출기간·한도 계산) / NOT_ELIGIBLE(대상 아님: 소득 없음·"
                    + "근로 가능 자격 미확인·급여 입금 3개월 미만) / CONDITION_NOT_MET(대상이지만 조건 미충족: 체류 만료 6개월 "
                    + "이내·산출 금리가 법정 최고금리 초과). 사유는 reasonCode.")
    @ApiResponse(responseCode = "200", description = "계산 결과(모의)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: isRefinance 가 true/false 가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "CONSENT_REQUIRED: CREDIT_EVALUATION 동의 없음·철회"
            + "(details.consentType = CREDIT_EVALUATION, CNS-02 로 동의)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_DATA: 입력을 만들 수 없음. details.reason "
            + "NO_VISA_PROFILE(체류 프로필 없음)·NO_STAY_START_DATE(현재 체류 시작일 없음)·NO_SUCCEEDED_BATCH(마이데이터 "
            + "적재 없음)·NO_COVERAGE·NO_FULL_MONTH(수집 기간에 꽉 찬 달 없음)·NO_TRANSACTIONS(집계할 거래 없음)·"
            + "INPUT_REJECTED(AI 가 입력을 거부, details.fields. 예: 누적 체류가 학습 범위 6~120개월 밖이면 "
            + "stayDurationMonths·out_of_training_range)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "AI_UPSTREAM_ERROR: 계산 서버 호출 실패(연결·타임아웃·오류 응답)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/alt-assessment")
    public AltCreditAssessmentResponse assess(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "다른 금융기관 대출을 갈아타는(대환) 경우 true. 우대금리 계산에만 쓴다", example = "false")
            @RequestParam(defaultValue = "false") boolean isRefinance) {
        return altCreditAssessmentService.assess(userId, isRefinance);
    }
}
