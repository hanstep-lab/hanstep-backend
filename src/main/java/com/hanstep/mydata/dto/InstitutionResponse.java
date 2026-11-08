package com.hanstep.mydata.dto;

import com.hanstep.mydata.domain.InstitutionType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "연결 가능한 기관(institutions)")
public record InstitutionResponse(
        @Schema(description = "기관 ID. MYD-02 institutionIds 로 보냄", example = "1")
        Long id,

        @Schema(description = "기관 코드", example = "SHINHAN_BANK")
        String institutionCode,

        @Schema(description = "기관명(영문)", example = "Shinhan Bank (Mock)")
        String name,

        @Schema(description = "화면 표시명(한국어)", example = "신한은행", nullable = true)
        String nameKo,

        @Schema(description = "업권", example = "BANK")
        InstitutionType institutionType,

        @Schema(description = "Mock 기관이면 true(MVP는 모두 Mock)", example = "true")
        boolean isMock
) {
}
