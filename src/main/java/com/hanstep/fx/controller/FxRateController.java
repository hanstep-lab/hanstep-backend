package com.hanstep.fx.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.fx.dto.FxRateHistoryResponse;
import com.hanstep.fx.dto.FxRateResponse;
import com.hanstep.fx.service.FxRateService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "FX Rate", description = "환율 정보 화면(FX-001). DB 에 모은 일별 고시 환율만 읽는다(실데이터만)")
@RestController
@RequestMapping("/api/v1/fx/rates")
public class FxRateController {

    // Reyes(PHL) 로컬 실제 출력(2026-11-18). PHP 는 Frankfurter ECB 실데이터 255영업일(2025-11-18 ~ 2026-11-17)
    static final String RATE_EXAMPLE = """
            {
              "currency": "PHP",
              "unit": 1,
              "rate": 20.984,
              "sendRate": null,
              "receiveRate": null,
              "change": 0.112,
              "changeRate": 0.54,
              "asOf": "2026-11-17",
              "sourceLabel": "ECB 기준환율 기반 참고값",
              "source": "MARKET_REF",
              "isMock": false,
              "rateAt": "2026-11-18T00:00:00+09:00",
              "fetchedAt": "2026-11-18T11:33:14+09:00",
              "stale": false,
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
              ],
              "screenCurrencies": [
                "USD",
                "PHP"
              ]
            }""";
    static final String HISTORY_EXAMPLE = """
            {
              "currency": "PHP",
              "period": "1M",
              "points": [
                {
                  "date": "2026-10-19",
                  "rate": 21.104
                },
                {
                  "date": "2026-10-20",
                  "rate": 21.032
                },
                {
                  "date": "2026-10-21",
                  "rate": 20.938
                },
                {
                  "date": "2026-10-22",
                  "rate": 20.857
                },
                {
                  "date": "2026-10-23",
                  "rate": 20.811
                },
                {
                  "date": "2026-10-26",
                  "rate": 20.726
                },
                {
                  "date": "2026-10-27",
                  "rate": 20.748
                },
                {
                  "date": "2026-10-28",
                  "rate": 20.948
                },
                {
                  "date": "2026-10-29",
                  "rate": 20.948
                },
                {
                  "date": "2026-10-30",
                  "rate": 20.968
                },
                {
                  "date": "2026-11-02",
                  "rate": 21.133
                },
                {
                  "date": "2026-11-03",
                  "rate": 21.188
                },
                {
                  "date": "2026-11-04",
                  "rate": 21.07
                },
                {
                  "date": "2026-11-05",
                  "rate": 21.346
                },
                {
                  "date": "2026-11-06",
                  "rate": 21.561
                },
                {
                  "date": "2026-11-09",
                  "rate": 21.318
                },
                {
                  "date": "2026-11-10",
                  "rate": 21.287
                },
                {
                  "date": "2026-11-11",
                  "rate": 21.137
                },
                {
                  "date": "2026-11-12",
                  "rate": 20.935
                },
                {
                  "date": "2026-11-13",
                  "rate": 20.954
                },
                {
                  "date": "2026-11-16",
                  "rate": 20.872
                },
                {
                  "date": "2026-11-17",
                  "rate": 20.984
                }
              ],
              "min": 20.726,
              "max": 21.561,
              "avg": 21.0393,
              "current": 20.984,
              "businessDays": 22,
              "reason": null,
              "source": "MARKET_REF",
              "isMock": false,
              "asOf": "2026-11-17"
            }""";

    private final FxRateService fxRateService;

    public FxRateController(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
    }

    @Operation(summary = "환율 정보 (FX-001)", description = """
            통화 1개의 일별 고시 환율(매매기준율), 송금 보낼 때·받을 때(수출입은행 고시만), 전 영업일 대비, 기간 평균 비교.
            화면 통화는 USD 와 모국 통화(국적 visa_profiles 기준, 필리핀 PHP) 두 개이고 screenCurrencies 로 준다.
            currency 를 빼면 모국 통화(없으면 USD).
            - 통화마다 우선 원천(KOREAEXIM > MARKET_REF > MOCK) 하나의 값만 쓴다. 운영은 실데이터만이라 isMock 은 항상 false
            - '기준'은 날짜(asOf)만. 원천에 고시 시각이 없어 시각은 fetchedAt(마지막 확인 시각)을 쓴다
            - PHP(MARKET_REF)는 참고 환율만: sendRate·receiveRate null, notices 에 REFERENCE_RATE_ONLY
            - 기간 평균: 1M·3M·6M·1Y 영업일 일별 고시값 평균과 현재 비교. 데이터가 기간보다 짧으면 그 기간은 null 과 INSUFFICIENT_DATA
            - 화면 문구(통화 이름·요약 문장·평균 설명)는 프론트가 만든다""")
    @ApiResponse(responseCode = "200", description = "성공", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = FxRateResponse.class),
            examples = @ExampleObject(name = "Reyes PHP", value = RATE_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: currency 가 내 화면 통화가 아님(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "FX_RATE_NOT_FOUND: 그 통화의 환율 실데이터가 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public FxRateResponse rate(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                               @Parameter(description = "통화(ISO 4217, 화면 통화만). 없으면 모국 통화", example = "PHP")
                               @RequestParam(required = false) String currency) {
        return fxRateService.rate(userId, currency);
    }

    @Operation(summary = "환율 추이 (FX-002)", description = """
            기간의 영업일별 일별 고시 환율(그 기준일의 마지막 값)과 최저·평균·최고·현재. 그래프용.
            기간은 기준일(마지막 영업일)부터 거슬러 1·3·6·12개월이고 시작일을 포함한다. 데이터가 기간보다 짧으면 avg 만 null.""")
    @ApiResponse(responseCode = "200", description = "성공", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = FxRateHistoryResponse.class),
            examples = @ExampleObject(name = "Reyes PHP 1M", value = HISTORY_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: currency 가 내 화면 통화가 아님, "
            + "period 가 1M·3M·6M·1Y(12M)이 아님(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "FX_RATE_NOT_FOUND: 그 통화의 환율 실데이터가 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{currency}/history")
    public FxRateHistoryResponse history(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                                         @Parameter(description = "통화(화면 통화만)", example = "PHP")
                                         @PathVariable String currency,
                                         @Parameter(description = "1M·3M·6M·1Y(12M 도 1Y). 기본 1M", example = "1M")
                                         @RequestParam(defaultValue = "1M") String period) {
        return fxRateService.history(userId, currency, period);
    }
}
