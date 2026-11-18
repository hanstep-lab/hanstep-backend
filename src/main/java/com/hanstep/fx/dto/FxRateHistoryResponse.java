package com.hanstep.fx.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 환율 화면 그래프(FX-002). 이름은 프론트(src/api/mock/fx.ts mockFxHistory)에 맞췄다. 값은 영업일별 일별 고시 환율이다. */
@Schema(description = "기간의 영업일별 일별 고시 환율(그래프용)과 최저·평균·최고·현재. 환율은 unit 외화당 원화")
public record FxRateHistoryResponse(
        @Schema(description = "통화", example = "PHP")
        String currency,

        @Schema(description = "기간. 1M·3M·6M·1Y", example = "1M")
        String period,

        @ArraySchema(arraySchema = @Schema(description = "영업일별 값(그 기준일의 마지막 고시값), 날짜 오름차순"))
        List<Point> points,

        @Schema(description = "기간 최저", example = "20.872")
        BigDecimal min,

        @Schema(description = "기간 최고", example = "21.716")
        BigDecimal max,

        @Schema(description = "기간 평균(소수 4자리 반올림). 데이터가 기간보다 짧으면 null(reason 참고)", nullable = true)
        BigDecimal avg,

        @Schema(description = "현재(마지막 영업일) 값", example = "20.984")
        BigDecimal current,

        @Schema(description = "기간에 있는 영업일 수", example = "22")
        int businessDays,

        @Schema(description = "avg 를 내지 않은 이유. INSUFFICIENT_DATA. 냈으면 null", nullable = true)
        String reason,

        @Schema(description = "출처. KOREAEXIM / MARKET_REF / MOCK(로컬 개발 전용)", example = "MARKET_REF")
        String source,

        @Schema(description = "기간 값에 Mock 이 있는지. 운영은 항상 false", example = "false")
        boolean isMock,

        @Schema(description = "기준일(마지막 영업일)", example = "2026-11-17")
        LocalDate asOf
) {

    @Schema(name = "FxRatePoint", description = "영업일 1개의 일별 고시 환율")
    public record Point(
            @Schema(description = "기준일", example = "2026-11-17") LocalDate date,
            @Schema(description = "매매기준율", example = "20.984") BigDecimal rate) {
    }
}
