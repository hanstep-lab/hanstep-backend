package com.hanstep.consent.dto;

import com.hanstep.consent.domain.ConsentStatus;
import com.hanstep.consent.domain.ConsentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "동의 종류별 최신 상태(user_consents 최신 행)")
public record ConsentResponse(
        @Schema(description = "동의 ID", example = "4")
        Long id,

        @Schema(description = "동의 종류", example = "MYDATA")
        ConsentType consentType,

        @Schema(description = "약관 버전", example = "mydata-2026-11")
        String policyVersion,

        @Schema(description = "동의 상태", example = "GRANTED")
        ConsentStatus status,

        @Schema(description = "동의 시각(+09:00)", example = "2026-11-07T10:04:12+09:00")
        OffsetDateTime grantedAt,

        @Schema(description = "철회 시각. GRANTED 면 null", example = "2026-12-01T09:00:00+09:00", nullable = true)
        OffsetDateTime revokedAt,

        @Schema(description = "만료 시각. 없으면 null", example = "2027-11-07T10:04:12+09:00", nullable = true)
        OffsetDateTime expiresAt
) {
}
