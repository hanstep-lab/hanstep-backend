package com.hanstep.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** OCR 결과 한 항목. value 가 null 이면 못 읽은 항목(MISSING). */
@Schema(description = "OCR 항목")
public record OcrFieldResult(
        @Schema(description = "nameEn, nationality, birthDate, visaType, stayExpiry, registrationNo 중 하나",
                example = "registrationNo")
        @NotBlank String fieldKey,

        @Schema(description = "추출값. 못 읽었으면 null(MISSING 으로 저장). stayExpiry 는 항상 null. "
                + "registrationNo 는 ######-#****** 마스킹 형식만", nullable = true, example = "950815-5******")
        @Size(max = 255) String value,

        @Schema(description = "신뢰도 0~1(소수 4자리로 반올림해 저장). 없으면 null", nullable = true, example = "0.9907")
        @DecimalMin("0") @DecimalMax("1") BigDecimal confidence,

        @Schema(description = "마스킹한 값인가. registrationNo 는 true 여야 한다", example = "true")
        @NotNull Boolean isMasked
) {
}
