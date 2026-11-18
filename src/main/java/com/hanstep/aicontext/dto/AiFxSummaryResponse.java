package com.hanstep.aicontext.dto;

import com.hanstep.fx.dto.FxRateResponse;
import com.hanstep.fx.dto.FxRateResponse.PeriodAverage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 챗봇 환율 진단 도구(AI fx_rate_summary)용 요약. 값은 환율 화면(FX-001, GET /api/v1/fx/rates)과 같은 계산(FxRateService.rate)
 * 결과를 그대로 옮긴다. 예측·권유 없이 과거 평균과의 비교만 준다. 화면 문구는 없다.
 */
@Schema(description = "챗봇 환율 진단용 요약. 통화별 값은 환율 화면(GET /api/v1/fx/rates)과 같은 계산이다")
public record AiFxSummaryResponse(
        @Schema(description = "사용자 ID", example = "4")
        long userId,

        @Schema(description = "사용자 화면 통화(USD + 모국 통화)", example = "[\"USD\", \"PHP\"]")
        List<String> screenCurrencies,

        @Schema(description = "통화별 요약. currency 를 주면 그 통화 1개")
        List<CurrencySummary> currencies,

        @Schema(description = "실데이터 환율이 없어 뺀 화면 통화(currency 생략 때만). 데이터 부족으로 다룬다", example = "[]")
        List<String> unavailableCurrencies
) {

    @Schema(name = "AiFxCurrencySummary", description = "통화 1개의 현재 값과 기간 평균 비교(FxRateResponse 와 같은 이름·값)")
    public record CurrencySummary(
            @Schema(description = "통화(ISO 4217)", example = "PHP")
            String currency,

            @Schema(description = "고시 단위. 환율은 unit 외화당 원화", example = "1")
            int unit,

            @Schema(description = "일별 고시 환율(매매기준율 또는 ECB 참고값)", example = "20.984")
            BigDecimal rate,

            @Schema(description = "송금 보낼 때 환율(수출입은행 고시만). 없으면 null", nullable = true)
            BigDecimal sendRate,

            @Schema(description = "송금 받을 때 환율(수출입은행 고시만). 없으면 null", nullable = true)
            BigDecimal receiveRate,

            @Schema(description = "전 영업일 대비(원). 직전 값이 없으면 null", nullable = true)
            BigDecimal change,

            @Schema(description = "전 영업일 대비(%, 소수 2자리). 직전 값이 없으면 null", nullable = true)
            BigDecimal changeRate,

            @Schema(description = "기준일(rate_date)", example = "2026-11-17")
            LocalDate asOf,

            @Schema(description = "원천. KOREAEXIM 수출입은행 고시 / MARKET_REF ECB 기준환율 참고값 / MOCK 로컬 개발용",
                    example = "MARKET_REF")
            String source,

            @Schema(description = "출처 표시 문구", example = "ECB 기준환율 기반 참고값")
            String sourceLabel,

            @Schema(description = "Mock 환율인지. 운영은 실데이터만이라 항상 false", example = "false")
            boolean isMock,

            @Schema(description = "값이 오래됐는지(KOREAEXIM 은 기준일이 직전 평일보다 이름, MARKET_REF 는 마지막 확인이 3시간 넘음)",
                    example = "false")
            boolean stale,

            @Schema(description = "원천 기준 시각(+09:00)")
            OffsetDateTime rateAt,

            @Schema(description = "마지막 확인 시각(+09:00)")
            OffsetDateTime fetchedAt,

            @Schema(description = "안내 코드. MARKET_REF 면 REFERENCE_RATE_ONLY(송금 환율 없음)")
            List<String> notices,

            @Schema(description = "기간 평균 비교(1M·3M·6M·1Y). 데이터가 기간보다 짧으면 avg 등이 null 이고 reason 이 INSUFFICIENT_DATA")
            List<PeriodAverage> periodAverages
    ) {

        public static CurrencySummary from(FxRateResponse r) {
            return new CurrencySummary(r.currency(), r.unit(), r.rate(), r.sendRate(), r.receiveRate(), r.change(),
                    r.changeRate(), r.asOf(), r.source(), r.sourceLabel(), r.isMock(), r.stale(), r.rateAt(),
                    r.fetchedAt(), r.notices(), r.periodAverages());
        }
    }
}
