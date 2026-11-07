package com.hanstep.onboarding.dto;

import com.hanstep.common.dto.Money;
import com.hanstep.onboarding.domain.ResidencePurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "온보딩 설문 응답(onboarding_profiles). 아직 저장 안 한 칸은 null")
public record OnboardingProfileResponse(
        @Schema(description = "거주 목적", example = "WORK", nullable = true)
        ResidencePurpose residencePurpose,

        @Schema(description = "자기신고 월소득. 입력 안 했으면 null",
                example = "{\"amount\": 2350000, \"currency\": \"KRW\"}")
        Money declaredMonthlyIncome,

        @Schema(description = "최초 입국일(YYYY-MM-DD). 국내 거주기간 계산용", example = "2022-12-05", nullable = true)
        LocalDate koreaEntryDate,

        @Schema(description = "거주 시도 코드(행정표준코드 시도 2자리. 41=경기도)", example = "41", nullable = true)
        String residenceRegionCode,

        @Schema(description = "사업주 숙소 제공 여부", example = "false", nullable = true)
        Boolean housingProvided
) {
}
