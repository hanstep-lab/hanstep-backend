package com.hanstep.onboarding.dto;

import com.hanstep.common.dto.Money;
import com.hanstep.onboarding.domain.ResidencePurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

@Schema(description = "온보딩 프로필 저장(1인 1행 UPSERT). 보낸 값으로 전체를 덮어쓰며 null 은 비운다")
public record OnboardingProfileRequest(
        @Schema(description = "ON-001 체류 목적", example = "WORK", nullable = true)
        ResidencePurpose residencePurpose,

        @Schema(description = "MVP에서는 받지 않음(null). 마이데이터 미연결 시 직접 입력은 2차 이후 검토",
                example = "{\"amount\": 2350000, \"currency\": \"KRW\"}")
        @Valid
        Money declaredMonthlyIncome
) {
}
