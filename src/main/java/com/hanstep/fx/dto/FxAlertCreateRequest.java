package com.hanstep.fx.dto;

import com.hanstep.fx.domain.FxComparator;
import com.hanstep.fx.domain.FxRateType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

@Schema(description = "목표 환율 알림 만들기. targetRate 는 그 통화 고시 단위(unitAmount)당 원화(예: VND 는 100 VND 당 원화)")
public record FxAlertCreateRequest(
        @Schema(description = "통화(ISO 4217 대문자 3자리). 환율이 저장된 통화만", example = "PHP")
        @NotNull
        @Pattern(regexp = "[A-Z]{3}", message = "must be 3 uppercase letters (ISO 4217)")
        String currency,

        @Schema(description = "비교할 환율. BASE 매매기준율 / SEND 송금 보낼 때 환율. 생략하면 SEND. "
                + "송금 보낼 때 환율이 없는 통화(시장 환율 참고값만 있는 통화, 예: VND)는 BASE 만 된다",
                example = "SEND", nullable = true)
        FxRateType rateType,

        @Schema(description = "LTE: 목표 이하가 되면 / GTE: 목표 이상이 되면", example = "LTE")
        @NotNull
        FxComparator comparator,

        @Schema(description = "목표 환율. 0보다 크고 소수 4자리까지(정수부 14자리까지)", example = "22.1")
        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        @Digits(integer = 14, fraction = 4, message = "must have at most 14 integer digits and 4 decimal places")
        BigDecimal targetRate
) {
}
