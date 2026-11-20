package com.hanstep.aicontext.controller;

import com.hanstep.aicontext.dto.AiFinanceContextResponse;
import com.hanstep.aicontext.dto.AiFxSummaryResponse;
import com.hanstep.aicontext.dto.AiProductProfileResponse;
import com.hanstep.aicontext.service.AiContextService;
import com.hanstep.common.config.OpenApiConfig;
import com.hanstep.common.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal · AI Context", description = "AI → Spring 컨텍스트 조회 (X-Internal-Token)")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN)
@RestController
@RequestMapping("/internal/v1/ai-context/users/{userId}")
public class AiContextInternalController {

    // Reyes(PHL) 로컬 실제 출력(2026-11-18, currency=PHP). PHP 는 Frankfurter ECB 실데이터 255영업일
    static final String FX_SUMMARY_EXAMPLE = """
            {
              "userId": 1,
              "screenCurrencies": [
                "USD",
                "PHP"
              ],
              "currencies": [
                {
                  "currency": "PHP",
                  "unit": 1,
                  "rate": 20.984,
                  "sendRate": null,
                  "receiveRate": null,
                  "change": 0.112,
                  "changeRate": 0.54,
                  "asOf": "2026-11-17",
                  "source": "MARKET_REF",
                  "sourceLabel": "ECB 기준환율 기반 참고값",
                  "isMock": false,
                  "stale": false,
                  "rateAt": "2026-11-18T00:00:00+09:00",
                  "fetchedAt": "2026-11-18T14:13:52+09:00",
                  "notices": [
                    "REFERENCE_RATE_ONLY"
                  ],
                  "periodAverages": [
                    {
                      "period": "1M",
                      "avg": 21.0393,
                      "diff": -0.0553,
                      "diffRate": -0.26,
                      "direction": "LOWER",
                      "businessDays": 22,
                      "reason": null
                    },
                    {
                      "period": "3M",
                      "avg": 21.5431,
                      "diff": -0.5591,
                      "diffRate": -2.6,
                      "direction": "LOWER",
                      "businessDays": 67,
                      "reason": null
                    },
                    {
                      "period": "6M",
                      "avg": 22.9364,
                      "diff": -1.9524,
                      "diffRate": -8.51,
                      "direction": "LOWER",
                      "businessDays": 132,
                      "reason": null
                    },
                    {
                      "period": "1Y",
                      "avg": 23.8157,
                      "diff": -2.8317,
                      "diffRate": -11.89,
                      "direction": "LOWER",
                      "businessDays": 255,
                      "reason": null
                    }
                  ]
                }
              ],
              "unavailableCurrencies": []
            }""";

    private final AiContextService aiContextService;

    public AiContextInternalController(AiContextService aiContextService) {
        this.aiContextService = aiContextService;
    }

    @Operation(summary = "AI 용 금융 컨텍스트 (INT-06)",
            description = "챗봇 도구 get_financial_analysis 가 부른다. 최신 분석(FIN-06), 월 수지 요약 balance"
                    + "(ONB-04 balance·PRD-02 여유자금과 같은 값, 여유자금은 balance.spare), 필수 확보액 합계(CMT-01 totals), "
                    + "금융 목표(GOL-01)를 준다. 계좌·잔액·계좌번호·거래처·생년월일·등록번호는 없다.")
    @ApiResponse(responseCode = "200", description = "성공. 완료된 분석이 없으면 latestAnalysis·balance 는 null")
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없는 사용자",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/finance")
    public AiFinanceContextResponse finance(@PathVariable Long userId) {
        return aiContextService.finance(userId);
    }

    @Operation(summary = "AI 용 상품 추천 프로필 (INT-19)",
            description = "챗봇 도구 recommend_savings_products 가 부른다. 비자 종류 코드, 체류 만료일, asOf(Asia/Seoul 오늘) 기준 "
                    + "만 나이를 준다. 생년월일은 내보내지 않는다. 여유자금은 INT-06 balance.spare 를 쓴다.")
    @ApiResponse(responseCode = "200", description = "성공. 비자 프로필이 없으면 visaTypeCode·stayExpiresOn·age 가 null")
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없는 사용자(USER_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/product-profile")
    public AiProductProfileResponse productProfile(@PathVariable Long userId) {
        return aiContextService.productProfile(userId);
    }

    @Operation(summary = "AI 용 환율 진단 요약 (챗봇 fx_rate_summary)",
            description = """
                    챗봇 도구 fx_rate_summary 가 부른다. 통화별 값은 환율 화면(GET /api/v1/fx/rates)과 같은 계산이다:                     일별 고시 환율, 전 영업일 대비, 기간 평균(1M·3M·6M·1Y) 비교, source·isMock·stale·rateAt·fetchedAt.
                    - 실데이터만(운영 isMock 은 항상 false). 데이터가 기간보다 짧으면 그 기간은 avg 가 null 이고 reason 이 INSUFFICIENT_DATA
                    - 과거 평균과의 비교만 준다(예측·권유 없음). 화면 문구는 없다
                    - currency 를 빼면 화면 통화(USD + 모국 통화) 전부. 실데이터가 없는 통화는 unavailableCurrencies 로 뺀다""")
    @ApiResponse(responseCode = "200", description = "성공", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AiFxSummaryResponse.class),
            examples = @ExampleObject(name = "Reyes", value = FX_SUMMARY_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: currency 가 그 사용자의 화면 통화가 아님(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND: 없는 사용자 / FX_RATE_NOT_FOUND: 그 통화(생략 때는 화면 통화 "
            + "전부)에 실데이터 환율이 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/fx-summary")
    public AiFxSummaryResponse fxSummary(@PathVariable Long userId,
                                         @Parameter(description = "통화(ISO 4217, 화면 통화만, 대소문자 무관). 없으면 화면 통화 전부",
                                                 example = "PHP")
                                         @RequestParam(required = false) String currency) {
        return aiContextService.fxSummary(userId, currency);
    }
}
