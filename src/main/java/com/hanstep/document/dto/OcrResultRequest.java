package com.hanstep.document.dto;

import com.hanstep.document.domain.DocumentSide;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * OCR 결과 보고 본문. fields 는 등록증 앞면 6개 항목(ArcFieldKey)을 한 번씩 담는다.
 * 항목 키·등록번호 마스킹 검사는 서비스에서 한다(오류에 값을 싣지 않는다).
 */
@Schema(description = "OCR 결과 보고(Worker → Spring)")
public record OcrResultRequest(
        @Schema(description = "경로의 documentId 와 같은 값", example = "42")
        @NotNull Long documentId,

        @Schema(description = "OCR 모델 버전(ocr_model_version)", example = "ppocrv5-ko-mobile-int8")
        @NotBlank @Size(max = 60) String modelVersion,

        @Schema(description = "OCR 처리 시간 ms(ocr_latency_ms)", example = "842")
        @NotNull @PositiveOrZero Integer latencyMs,

        @Schema(description = "처리한 면. FRONT 만 받는다", example = "FRONT")
        @NotNull DocumentSide side,

        @ArraySchema(arraySchema = @Schema(description = "nameEn, nationality, birthDate, visaType, stayExpiry, "
                + "registrationNo 를 한 번씩"))
        @NotNull List<@NotNull @Valid OcrFieldResult> fields
) {
}
