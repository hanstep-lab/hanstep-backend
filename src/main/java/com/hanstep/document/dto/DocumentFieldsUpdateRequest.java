package com.hanstep.document.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** DOC-03 OCR 항목 확인·수정. 보낸 항목만 바꾼다. 등록번호(registrationNo)는 고칠 수 없다. */
@Schema(description = "OCR 항목 확인·수정")
public record DocumentFieldsUpdateRequest(
        @ArraySchema(arraySchema = @Schema(description = "바꿀 항목(같은 fieldKey 는 한 번만)"))
        @NotEmpty List<@NotNull @Valid Item> fields
) {

    @Schema(name = "DocumentFieldUpdateItem", description = "항목 하나")
    public record Item(
            @Schema(description = "nameEn · nationality · birthDate · visaType · stayExpiry", example = "stayExpiry")
            @NotBlank String fieldKey,
            @Schema(description = "확인한 값. nationality 는 ISO 3166-1 alpha-3(예: PHL), 날짜는 YYYY-MM-DD, "
                    + "stayExpiry 는 오늘 이전 불가, visaType 은 체류자격 코드(예: E-9)", example = "2027-03-01")
            @NotBlank @Size(max = 100) String value
    ) {
    }
}
