package com.hanstep.fx.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 환율 화면(FX-001) 통화 1개. 이름은 프론트(src/api/mock/fx.ts mockFxRate)에 맞췄다. 환율은 unit 외화당 원화이고
 * 그 rate_date 의 일별 고시값이다. 화면 문구(통화 이름, 요약 문장, 평균 설명)는 서버가 만들지 않는다.
 */
@Schema(description = "통화 1개의 현재 일별 고시 환율, 전 영업일 대비, 기간 평균 비교. 환율은 unit 외화당 원화(DB 에 모은 일별 고시값)")
public record FxRateResponse(
        @Schema(description = "통화(ISO 4217)", example = "PHP")
        String currency,

        @Schema(description = "고시 단위(VND 등은 100)", example = "1")
        int unit,

        @Schema(description = "매매기준율(그 기준일의 일별 고시값, 끝자리 0 제거)", example = "20.984")
        BigDecimal rate,

        @Schema(description = "송금 보낼 때 환율. 수출입은행 고시(KOREAEXIM)만 있고 PHP(MARKET_REF)는 null", nullable = true)
        BigDecimal sendRate,

        @Schema(description = "송금 받을 때 환율. 수출입은행 고시(KOREAEXIM)만 있고 PHP(MARKET_REF)는 null", nullable = true)
        BigDecimal receiveRate,

        @Schema(description = "전 영업일 대비(rate − 전 영업일 값, 소수 4자리 반올림). 전 영업일 값이 없으면 null",
                example = "0.112", nullable = true)
        BigDecimal change,

        @Schema(description = "전 영업일 대비(%) = change ÷ 전 영업일 값 × 100, 소수 2자리 반올림. 없으면 null",
                example = "0.54", nullable = true)
        BigDecimal changeRate,

        @Schema(description = "기준일(rateDate). 원천에 고시 시각이 없어 '기준'은 날짜만 쓴다", example = "2026-11-17")
        LocalDate asOf,

        @Schema(description = "출처 표시 문구. KOREAEXIM 은 수출입은행 고시 매매기준율(은행이 정한 송금 환율이 아님), "
                + "MARKET_REF 는 ECB 기준환율 기반 참고값", example = "ECB 기준환율 기반 참고값")
        String sourceLabel,

        @Schema(description = "출처. KOREAEXIM(수출입은행) / MARKET_REF(Frankfurter ECB) / MOCK(로컬 개발 전용)",
                example = "MARKET_REF")
        String source,

        @Schema(description = "Mock 환율인지. 운영은 항상 false(실데이터만)", example = "false")
        boolean isMock,

        @Schema(description = "값의 기준 시각(+09:00). KOREAEXIM 은 기준일 00:00 KST, MARKET_REF 는 ECB 고시 시각(16:00 CET). "
                + "화면 표시는 asOf(날짜)를 쓴다", example = "2026-11-18T00:00:00+09:00")
        OffsetDateTime rateAt,

        @Schema(description = "마지막 확인 시각(+09:00). 수집기가 원천에서 이 값을 마지막으로 확인한 때(화면 '업데이트 확인', 임시)",
                example = "2026-11-18T10:50:45+09:00")
        OffsetDateTime fetchedAt,

        @Schema(description = "오래된 값인지(임시 기준). KOREAEXIM·MOCK 은 asOf 가 직전 영업일(주말 제외, 공휴일 모름)보다 "
                + "이르면, MARKET_REF 는 fetchedAt 이 3시간 넘게 지났으면 true. 장애 시에도 마지막 값을 준다", example = "false")
        boolean stale,

        @ArraySchema(arraySchema = @Schema(description = "안내 코드. REFERENCE_RATE_ONLY: 참고 환율만 있고 송금 환율은 없음"
                + "(실제 송금 환율은 은행·송금 방식에 따라 다름). 문구는 프론트 언어별 문구표"))
        List<String> notices,

        @ArraySchema(arraySchema = @Schema(description = "기간 평균 비교. 1M·3M·6M·1Y 순"))
        List<PeriodAverage> periodAverages,

        @ArraySchema(arraySchema = @Schema(description = "이 사용자의 화면 통화. USD 와 모국 통화(국적 기준). "
                + "국적이 없거나 매핑이 없으면 USD 만", example = "[\"USD\", \"PHP\"]"))
        List<String> screenCurrencies
) {

    @Schema(name = "FxPeriodAverage", description = "기간 평균 비교. 평균 = 그 기간 영업일 일별 고시값(영업일별 마지막 값)의 평균")
    public record PeriodAverage(
            @Schema(description = "기간. 1M·3M·6M·1Y(기준일부터 거슬러 1·3·6·12개월, 시작일 포함)", example = "1M")
            String period,

            @Schema(description = "평균(소수 4자리 반올림). 데이터가 기간보다 짧으면 null", example = "21.3305", nullable = true)
            BigDecimal avg,

            @Schema(description = "현재 − 평균(소수 4자리). 데이터 부족이면 null", example = "-0.3465", nullable = true)
            BigDecimal diff,

            @Schema(description = "(현재 − 평균) ÷ 평균 × 100(%, 소수 2자리 반올림). 데이터 부족이면 null",
                    example = "-1.62", nullable = true)
            BigDecimal diffRate,

            @Schema(description = "현재가 평균보다 LOWER(낮음) / HIGHER(높음) / SAME(±0.10% 이내, 임시 기준). 데이터 부족이면 null",
                    example = "LOWER", nullable = true)
            String direction,

            @Schema(description = "평균에 쓴 영업일 수(데이터 부족이면 그 기간에 있는 영업일 수)", example = "22")
            int businessDays,

            @Schema(description = "평균을 내지 않은 이유. INSUFFICIENT_DATA(데이터가 기간 시작보다 늦게 시작). 냈으면 null",
                    nullable = true)
            String reason
    ) {
    }
}
