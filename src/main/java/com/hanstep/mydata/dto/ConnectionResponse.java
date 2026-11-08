package com.hanstep.mydata.dto;

import com.hanstep.mydata.domain.ConnectionStatus;
import com.hanstep.mydata.domain.MydataScope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "기관별 연동 상태(data_connections + 최근 ingestion_batches)")
public record ConnectionResponse(
        @Schema(description = "연동 ID", example = "1")
        Long id,

        @Schema(description = "기관 ID", example = "1")
        Long institutionId,

        @Schema(description = "기관 코드", example = "SHINHAN_BANK")
        String institutionCode,

        @Schema(description = "화면 표시명", example = "신한은행")
        String institutionName,

        @Schema(description = "연동 상태", example = "CONNECTED")
        ConnectionStatus status,

        @Schema(description = "불러온 정보", example = "[\"ACCOUNT_BALANCE\", \"TRANSACTIONS\"]")
        List<MydataScope> scopes,

        @Schema(description = "조회 기간(개월)", example = "12")
        int lookbackMonths,

        @Schema(description = "최근 수집 시각(+09:00)", example = "2026-11-07T10:06:03+09:00", nullable = true)
        OffsetDateTime lastSyncedAt,

        @Schema(description = "가져온 계좌 수", example = "2")
        int accountCount,

        @Schema(description = "가져온 거래 수", example = "184")
        int transactionCount,

        @Schema(description = "Mock 데이터면 true", example = "true")
        boolean isMock
) {
}
