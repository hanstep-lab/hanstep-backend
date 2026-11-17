package com.hanstep.credit.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.credit.dto.SolGlobalLoanResponse;
import com.hanstep.credit.service.SolGlobalLoanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Credit (Mock)", description = "대안신용 모의 사전진단. 모의 사전진단이며 실제 대출 승인이나 신용점수가 아닙니다")
@RestController
@RequestMapping("/api/v1/credit/loans")
public class SolGlobalLoanController {

    private final SolGlobalLoanService solGlobalLoanService;

    public SolGlobalLoanController(SolGlobalLoanService solGlobalLoanService) {
        this.solGlobalLoanService = solGlobalLoanService;
    }

    @Operation(summary = "SOL 글로벌론 상품 상세(CR-002)",
            description = "신한은행 SOL 글로벌론(외국인 전용 직장인 신용대출) 상품 조건과 로그인 사용자의 가입 조건 5개"
                    + "(salary 재직·급여이체, visa 체류자격 E-9·E-7·F-2·F-5, nationality 대상 국적 7개국, stay 체류 만료까지 "
                    + "6개월 초과, income 연소득 2,000만 원 이상) 충족 여부, 신한걸음 자체 모의 한도(limitEstimate)를 준다. "
                    + "금리 숫자는 주지 않는다(상품설명서에서 확인). 저장하지 않는다(부를 때마다 다시 계산).\n\n"
                    + "대안신용 모의 사전진단(GET /api/v1/credit/alt-assessment)과 다른 점: 상품 안내라 CREDIT_EVALUATION "
                    + "동의를 요구하지 않고, AI 계산(모델 V9)을 부르지 않는다. 신용점수·금리를 내지 않고 상품 고유 조건과 "
                    + "한도 산식 Min(연소득 × 80%, 연소득 ÷ 12 × 40% × 대출개월수, 5,000만 원) − 기존 대출만 서버에서 계산한다. "
                    + "DSR·타행 대출 심사는 반영하지 않는다(dsrApplied=false). "
                    + "급여 입금 개월·신한 급여 계좌·만료까지 개월은 대안신용과 같은 입력을 쓰므로 입력이 모자라면 같은 422 가 난다. "
                    + "연소득은 임시로 income_records 의 가장 최근 VERIFIED 연도, 없으면 현 직장 고용계약 연봉"
                    + "(limitEstimate.incomeSource).")
    @ApiResponse(responseCode = "200", description = "상품 상세와 가입 조건 확인(모의, isMock=true)")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_DATA: 가입 조건을 확인할 입력이 없음(대안신용과 같음). "
            + "details.reason NO_VISA_PROFILE·NO_STAY_START_DATE·NO_SUCCEEDED_BATCH·NO_COVERAGE·NO_FULL_MONTH·"
            + "NO_TRANSACTIONS",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/sol-global")
    public SolGlobalLoanResponse solGlobal(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return solGlobalLoanService.get(userId);
    }
}
