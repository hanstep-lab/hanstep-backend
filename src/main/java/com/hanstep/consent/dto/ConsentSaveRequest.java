package com.hanstep.consent.dto;

import com.hanstep.consent.domain.ConsentType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "동의 일괄 저장. 마이데이터 연결(ON-003A)에서는 MYDATA·MYDATA_TERMS 가 모두 agreed=true 여야 한다. "
        + "agreed=false 는 철회로 기록(이력 유지)")
public record ConsentSaveRequest(
        @ArraySchema(arraySchema = @Schema(description = "저장할 동의 목록"))
        @NotEmpty
        @Size(max = 10)
        List<@Valid @NotNull Item> consents
) {

    @Schema(name = "ConsentSaveItem", description = "동의 하나")
    public record Item(
            @Schema(description = "동의 종류", example = "MYDATA")
            @NotNull
            ConsentType consentType,

            @Schema(description = "화면에 보여 준 약관 버전", example = "mydata-2026-11")
            @NotBlank
            @Size(max = 40)
            String policyVersion,

            @Schema(description = "true=동의, false=철회", example = "true")
            @NotNull
            Boolean agreed
    ) {
    }
}
