package com.hanstep.visa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** 체류 정보 직접 저장(PUT /api/v1/visa/profile) 응답. 저장된 등록증 항목 4개와 남은 일수. */
@Schema(description = "저장된 체류 정보")
public record VisaProfileResponse(
        @Schema(description = "국적(ISO 3166-1 alpha-3)", example = "PHL")
        String nationality,
        @Schema(description = "생년월일", example = "1995-08-15")
        LocalDate birthDate,
        @Schema(description = "체류자격 코드(visa_types.code)", example = "E-9")
        String visaType,
        @Schema(description = "체류 만료일", example = "2027-03-01")
        LocalDate stayExpiry,
        @Schema(description = "체류 만료일까지 남은 일수(오늘 KST 기준, 지났으면 음수). ONB-04 와 같은 계산", example = "103")
        long daysRemaining,
        @Schema(description = "증빙 상태. 직접 입력이라 DECLARED", example = "DECLARED")
        String evidenceStatus
) {
}
