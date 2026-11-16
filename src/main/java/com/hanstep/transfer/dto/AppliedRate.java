package com.hanstep.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "견적에 쓴 참고 환율. unit 외화 = rate 원(exchange_rates 매매기준율). 실시간 환율 아님")
public record AppliedRate(
        @Schema(description = "통화(ISO 4217)", example = "PHP")
        String currency,

        @Schema(description = "고시 단위(VND 등은 100)", example = "1")
        int unit,

        @Schema(description = "unit 외화당 원화(끝자리 0 제거, 지수 표기 없음)", example = "22.32")
        BigDecimal rate,

        @Schema(description = "출처 표시 문구", example = "ECB 기준환율 기반 참고값")
        String sourceLabel,

        @Schema(description = "기준일(KST)", example = "2026-11-06")
        LocalDate asOf,

        @Schema(description = "데모 환율 여부", example = "true")
        boolean isMock
) {
}
