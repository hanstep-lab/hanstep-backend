package com.hanstep.fx.dto;

import com.hanstep.fx.domain.FxAlertStatus;
import com.hanstep.fx.domain.FxComparator;
import com.hanstep.fx.domain.FxRateType;
import com.hanstep.fx.repository.FxAlertRepository.AlertRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Schema(description = "목표 환율 알림(fx_alerts). 환율 값은 unitAmount 외화당 원화")
public record FxAlertResponse(
        @Schema(description = "알림 ID", example = "1")
        Long alertId,

        @Schema(description = "통화(ISO 4217)", example = "PHP")
        String currency,

        @Schema(description = "비교할 환율. BASE 매매기준율 / SEND 송금 보낼 때 환율", example = "SEND")
        FxRateType rateType,

        @Schema(description = "LTE: 목표 이하가 되면 / GTE: 목표 이상이 되면", example = "LTE")
        FxComparator comparator,

        @Schema(description = "목표 환율(끝자리 0 제거, 지수 표기 없음)", example = "22.1")
        BigDecimal targetRate,

        @Schema(description = "고시 단위. 그 통화 최신 환율 행 기준(VND 등은 100). 환율 행이 없어졌으면 null",
                example = "1", nullable = true)
        Integer unitAmount,

        @Schema(description = "ACTIVE: 기다리는 중 / TRIGGERED: 목표 도달. 지운 알림(CANCELLED)은 목록에 없다",
                example = "ACTIVE")
        FxAlertStatus status,

        @Schema(description = "만든 시각(+09:00)", example = "2026-11-17T14:30:00+09:00")
        OffsetDateTime createdAt,

        @Schema(description = "도달 시각(+09:00). TRIGGERED 가 아니면 null", nullable = true)
        OffsetDateTime triggeredAt,

        @Schema(description = "도달 판정에 쓴 환율(rateType 의 값, 끝자리 0 제거). TRIGGERED 가 아니면 null", nullable = true)
        BigDecimal triggeredRate,

        @Schema(description = "앱이 도달을 알렸다고 보낸 시각(+09:00, POST /{alertId}/notified). 아직이면 null",
                nullable = true)
        OffsetDateTime notifiedAt
) {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    public static FxAlertResponse from(AlertRow row) {
        return new FxAlertResponse(row.id(), row.currency(), row.rateType(), row.comparator(), plain(row.targetRate()),
                row.unitAmount(), row.status(), kst(row.createdAt()), kst(row.triggeredAt()),
                plain(row.triggeredRate()), kst(row.notifiedAt()));
    }

    /** 송금 견적 환율(AppliedRate.rate)과 같은 표기. 22.1000 → 22.1, 1350.0000 → 1350(1.35E+3 이 되지 않게). */
    static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    static OffsetDateTime kst(Instant at) {
        return at == null ? null : at.atOffset(KST).truncatedTo(ChronoUnit.SECONDS);
    }
}
