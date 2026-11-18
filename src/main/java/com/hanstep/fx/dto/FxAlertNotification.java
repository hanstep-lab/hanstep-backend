package com.hanstep.fx.dto;

import com.hanstep.fx.domain.FxComparator;
import com.hanstep.fx.domain.FxRateType;
import com.hanstep.fx.domain.FxSource;
import com.hanstep.fx.repository.FxAlertRepository.NotificationRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 아직 알리지 않은 목표 환율 도달 1건. GET /api/v1/fx/alerts/notifications 의 항목이자 스트림 event: fx-alert-triggered 의
 * data 다. 알림 문장은 서버가 만들지 않는다(앱이 화면 언어로 만든다).
 */
@Schema(name = "FxAlertNotification", description = "아직 알리지 않은 목표 환율 도달(TRIGGERED, notifiedAt 없음). "
        + "스트림 event: fx-alert-triggered 의 data 와 같다. 환율 값은 unitAmount 외화당 원화이고 실데이터(Mock 아님)다")
public record FxAlertNotification(
        @Schema(description = "알림 ID. 스트림 이벤트 id 와 같다. 알렸으면 이 값으로 POST /{alertId}/notified", example = "12")
        Long alertId,

        @Schema(description = "통화(ISO 4217)", example = "PHP")
        String currency,

        @Schema(description = "비교한 환율. BASE 매매기준율 / SEND 송금 보낼 때 환율", example = "BASE")
        FxRateType rateType,

        @Schema(description = "LTE: 목표 이하가 되면 / GTE: 목표 이상이 되면", example = "GTE")
        FxComparator comparator,

        @Schema(description = "목표 환율(끝자리 0 제거)", example = "23.5")
        BigDecimal targetRate,

        @Schema(description = "고시 단위. 도달 판정에 쓴 환율 행 값(VND 등은 100)", example = "1")
        int unitAmount,

        @Schema(description = "도달 판정에 쓴 환율(rateType 의 값, 끝자리 0 제거)", example = "23.6154")
        BigDecimal triggeredRate,

        @Schema(description = "도달 시각(+09:00)", example = "2026-11-18T15:02:11+09:00")
        OffsetDateTime triggeredAt,

        @Schema(description = "도달 판정에 쓴 환율의 기준일", example = "2026-11-17")
        LocalDate rateDate,

        @Schema(description = "도달 판정에 쓴 환율의 출처. KOREAEXIM(수출입은행) / MARKET_REF(Frankfurter ECB 기준환율 참고값)",
                example = "MARKET_REF")
        FxSource source
) {

    public static FxAlertNotification from(NotificationRow row) {
        return new FxAlertNotification(row.id(), row.currency(), row.rateType(), row.comparator(),
                FxAlertResponse.plain(row.targetRate()), row.unitAmount(), FxAlertResponse.plain(row.triggeredRate()),
                FxAlertResponse.kst(row.triggeredAt()), row.rateDate(), row.source());
    }
}
