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
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Finance Products", description = "금융상품 추천(PRD-02). 상품설명서 원문 기준, 여유자금은 Mock 분석 값")
@RestController
@RequestMapping("/api/v1/finance/products")
public class ProductRecommendController {

    // 예시 세 개는 AI 추천기(hanstep-AI a79708a, 2026-11-17 조건표)의 실제 결과에서 상품 일부만 남긴 것이다
    static final String SUCCESS_EXAMPLE = """
            {"asOf": "2026-11-17", "remainingStayMonths": 10, "rankedBy": "catalog_no_query", "abstainReason": null,
             "products": [
               {"productId": "doc-a32e7bfdf57e", "nameKo": "슈퍼SOL 웰컴 정기예금", "kind": "DEPOSIT",
                "termMonths": [6, 12], "monthlyAmountKrw": null,
                "depositAmountKrw": {"min": 500000, "max": 20000000}, "accrual": null,
                "foreignerEligible": "inferred", "notChecked": ["visa"], "stayFits": true,
                "baseRate": {"ratePct": 3.3, "asOf": "2026-11-09",
                             "quote": "기본이자율 (2026.11.09 현재, 세전) 6개월 : 연 3.3% 12개월 : 연 3.6%", "page": 2},
                "maxRate": null,
                "feature": {"quote": "신한 슈퍼 SOL 신규 고객 및 급여 첫 거래 고객에게 우대금리를 제공하는 정기예금", "page": 1},
                "evidence": [
                  {"about": "term", "quote": "계약기간 6개월, 12개월", "page": 1},
                  {"about": "amount", "quote": "50만원 이상 2,000만원 이하", "page": 1},
                  {"about": "foreigners", "quote": "실명의 개인 및 개인사업자", "page": 1}]},
               {"productId": "doc-de733331abd3", "nameKo": "신한 땡겨요 적금", "kind": "SAVINGS",
                "termMonths": {"min": 6, "max": 6}, "monthlyAmountKrw": {"min": 1000, "max": 300000},
                "depositAmountKrw": null, "accrual": "FLEXIBLE",
                "foreignerEligible": "inferred", "notChecked": ["visa", "age"], "stayFits": true,
                "baseRate": {"ratePct": 2.2, "asOf": "2026-10-01",
                             "quote": "기본이자율 (2026.10.01 현재, 세전) 연 2.20%", "page": 1},
                "maxRate": {"ratePct": 3.7, "quote": "최고이자율 (2026.10.01 현재, 세전) 최고 연 3.70%", "page": 2},
                "feature": {"quote": "신한은행 배달앱 “땡겨요” 서비스를 이용하거나 주단위 적금 납입 미션 달성 고객에게 우대금리를 제공하는 적립식 상품", "page": 1},
                "evidence": [
                  {"about": "term", "quote": "계약기간 6개월", "page": 1},
                  {"about": "amount", "quote": "최소 1천원 이상 월 30만원 이하", "page": 1},
                  {"about": "accrual", "quote": "상품유형 자유적립식", "page": 1},
                  {"about": "foreigners", "quote": "가입대상 실명의 개인 및 개인사업자", "page": 1}]}],
             "excludedSummary": [{"reason": "recommendable", "count": 1}, {"reason": "stayTerm", "count": 5}],
             "isMock": true}""";

    static final String ABSTAIN_EXAMPLE = """
            {"asOf": "2026-11-17", "remainingStayMonths": 10, "rankedBy": "catalog_no_query",
             "abstainReason": "REQUEST_EXCEEDS_SURPLUS", "products": [],
             "excludedSummary": [{"reason": "recommendable", "count": 1}, {"reason": "kind", "count": 4},
                                 {"reason": "stayTerm", "count": 5}, {"reason": "requestMonthly", "count": 3}],
             "isMock": true}""";

    static final String NULL_RATE_EXAMPLE = """
            {"asOf": "2026-11-17", "remainingStayMonths": null, "rankedBy": "catalog_no_query", "abstainReason": null,
             "products": [
               {"productId": "doc-b89237ab049e", "nameKo": "신한 스마트 적금", "kind": "SAVINGS",
                "termMonths": {"min": 12, "max": 12}, "monthlyAmountKrw": {"min": 1000, "max": 1000000},
                "depositAmountKrw": null, "accrual": "FLEXIBLE", "foreignerEligible": "inferred",
                "notChecked": ["visa", "age", "remainingStay", "spareMoney"], "stayFits": null,
                "baseRate": {"ratePct": 3.15, "asOf": "2026-10-01", "quote": "기본이자율 연 3.15%", "page": 1},
                "maxRate": null,
                "feature": {"quote": "복잡한 우대금리요건 없이 간단하게 스마트폰뱅킹에서 가입 가능한 스마트폰 전용 적금", "page": 1},
                "evidence": [
                  {"about": "term", "quote": "계약기간 1년제", "page": 1},
                  {"about": "amount", "quote": "가입금액 1천원 이상", "page": 1},
                  {"about": "accrual", "quote": "상품유형 자유적립식", "page": 1},
                  {"about": "foreigners", "quote": "실명의 개인 및 개인사업자(1인 1계좌)", "page": 1}]}],
             "excludedSummary": [{"reason": "recommendable", "count": 1}, {"reason": "kind", "count": 4}],
             "isMock": true}""";

    private final ProductRecommendService productRecommendService;

    public ProductRecommendController(ProductRecommendService productRecommendService) {
        this.productRecommendService = productRecommendService;
    }

    @Operation(summary = "금융상품 추천(PRD-02)",
            description = """
                    사용자 프로필(비자 종류·체류 만료일·만 나이)과 최신 금융 분석의 월 여유자금(Mock 거래 기준)으로 \
                    조건표 상품(적금·정기예금) 중 가입 조건을 통과한 상품을 전부 준다. 챗봇 상품 추천과 같은 추천기이며 \
                    LLM 을 부르지 않는다. 저장하지 않는다(부를 때마다 다시 판정).
                    금리(baseRate·maxRate)와 특징(feature)은 상품설명서 원문 값만 옮긴다. 우대금리를 더하거나 계산한 \
                    숫자는 없고, 근거가 없으면 null 이다(화면은 금리를 표시하지 않음). 금리는 baseRate.asOf 와 함께 표시한다.
                    maxRate 는 우대 조건 충족 시 최고 금리(원문 값)이므로 '우대 조건 충족 시' 문구와 함께 표시한다.
                    판정할 프로필 값·근거가 없는 조건은 상품의 notChecked 에 있다(분석 결과가 없으면 spareMoney).
                    통과한 상품이 없으면(기권) 200 으로 products=[] 와 abstainReason 을 준다.""")
    @ApiResponse(responseCode = "200", description = "추천 결과(기권 포함)", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ProductRecommendResponse.class),
            examples = {@ExampleObject(name = "성공", value = SUCCESS_EXAMPLE),
                    @ExampleObject(name = "기권", description = "E-9·체류 10개월·여유자금 675,317원 사용자가 "
                            + "productType=SAVINGS·monthlyAmountKrw=1000000 으로 조회(요청 월 납입액이 여유자금보다 큼)",
                            value = ABSTAIN_EXAMPLE),
                    @ExampleObject(name = "금리 null", description = "원문에 최고(최종) 이자율이 없는 상품은 maxRate=null"
                            + "(기본금리 근거가 없으면 baseRate 도 null). 비자 프로필·분석 결과가 없는 사용자라 "
                            + "remainingStayMonths·stayFits 도 null 이고 notChecked 에 모든 프로필 조건이 있음",
                            value = NULL_RATE_EXAMPLE)}))
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
