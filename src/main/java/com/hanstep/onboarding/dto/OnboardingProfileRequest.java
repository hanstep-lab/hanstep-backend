package com.hanstep.onboarding.dto;

import com.hanstep.common.dto.Money;
import com.hanstep.onboarding.domain.ResidencePurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

@Schema(description = "온보딩 프로필 저장(1인 1행 UPSERT). 보낸 값으로 전체를 덮어쓰며 null 은 비운다")
public record OnboardingProfileRequest(
        @Schema(description = "거주 목적", example = "WORK", nullable = true)
        ResidencePurpose residencePurpose,

        @Schema(description = "자기신고 월소득. 입력 안 했으면 null",
                example = "{\"amount\": 2350000, \"currency\": \"KRW\"}")
        @Valid
        Money declaredMonthlyIncome,

        @Schema(description = "최초 입국일(YYYY-MM-DD)", example = "2022-12-05", nullable = true)
        @PastOrPresent
        LocalDate koreaEntryDate,

        @Schema(description = "거주 시도 코드(행정표준코드 시도 2자리)", example = "41", nullable = true)
        @Size(max = 20)
        String residenceRegionCode,

        @Schema(description = "사업주 숙소 제공 여부", example = "false", nullable = true)
        Boolean housingProvided
) {
}
