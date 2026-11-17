package com.hanstep.product.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.product.dto.ProductRecommendConditions;
import com.hanstep.product.dto.ProductRecommendConditions.Accrual;
import com.hanstep.product.dto.ProductRecommendConditions.ProductType;
import com.hanstep.product.dto.ProductRecommendResponse;
import com.hanstep.product.service.ProductRecommendService;
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

@Tag(name = "Finance Products", description = "금융상품 추천(PRD-01). 상품설명서 원문 기준, 여유자금은 Mock 분석 값")
@RestController
@RequestMapping("/api/v1/finance/products")
public class ProductRecommendController {

    private final ProductRecommendService productRecommendService;

    public ProductRecommendController(ProductRecommendService productRecommendService) {
        this.productRecommendService = productRecommendService;
    }

    @Operation(summary = "금융상품 추천(PRD-01)",
            description = """
                    사용자 프로필(비자 종류·체류 만료일·만 나이)과 최신 금융 분석의 월 여유자금(Mock 거래 기준)으로 \
                    조건표 상품(적금·정기예금) 중 가입 조건을 통과한 상품을 전부 준다. 챗봇 상품 추천과 같은 추천기이며 \
                    LLM 을 부르지 않는다. 저장하지 않는다(부를 때마다 다시 판정).
                    금리(baseRate·maxRate)와 특징(feature)은 상품설명서 원문 값만 옮긴다. 우대금리를 더하거나 계산한 \
                    숫자는 없고, 근거가 없으면 null 이다(화면은 금리를 표시하지 않음). 금리는 baseRate.asOf 와 함께 표시한다.
                    maxRate 는 우대 조건 충족 시 최고 금리(원문 값)이므로 '우대 조건 충족 시' 문구와 함께 표시한다.
                    판정할 프로필 값·근거가 없는 조건은 상품의 notChecked 에 있다(분석 결과가 없으면 spareMoney).
                    통과한 상품이 없으면(기권) 200 으로 products=[] 와 abstainReason 을 준다.""")
    @ApiResponse(responseCode = "200", description = "추천 결과(기권 포함)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR(details.fields): productType·accrual 이 정해진 값이 "
            + "아님, maxTermMonths 가 1~120 밖, monthlyAmountKrw 가 1~100,000,000 밖, requireForeignerExplicit 이 "
            + "true/false 가 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND: 토큰의 사용자가 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "AI_UPSTREAM_ERROR: 추천 서버 호출 실패(연결·타임아웃·오류 응답)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/recommend")
    public ProductRecommendResponse recommend(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "SAVINGS(적금) / DEPOSIT(정기예금)")
            @RequestParam(required = false) ProductType productType,
            @Parameter(description = "받아들이는 가장 긴 계약 기간(개월), 1~120", example = "12")
            @RequestParam(required = false) Integer maxTermMonths,
            @Parameter(description = "매달 넣을 금액(원), 1~100,000,000. 여유자금보다 크면 기권 REQUEST_EXCEEDS_SURPLUS")
            @RequestParam(required = false) Long monthlyAmountKrw,
            @Parameter(description = "FLEXIBLE(자유적립) / FIXED(정액적립)")
            @RequestParam(required = false) Accrual accrual,
            @Parameter(description = "true 면 상품설명서에 외국인 가입이 명시된 상품만. 기본 false")
            @RequestParam(required = false) Boolean requireForeignerExplicit) {
        return productRecommendService.recommend(userId, new ProductRecommendConditions(productType, maxTermMonths,
                monthlyAmountKrw, accrual, requireForeignerExplicit));
    }
}
