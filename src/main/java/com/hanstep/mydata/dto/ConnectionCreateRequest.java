package com.hanstep.mydata.dto;

import com.hanstep.mydata.domain.MydataScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "ON-003B '확인하고 연결'. 고른 기관을 연결하고 Mock 계좌·거래를 적재한다")
public record ConnectionCreateRequest(
        @Schema(description = "연결할 기관 ID(MYD-01)", example = "[1, 2]")
        @NotEmpty
        List<@NotNull Long> institutionIds,

        @Schema(description = "불러올 정보",
                example = "[\"ACCOUNT_BALANCE\", \"TRANSACTIONS\", \"CARD_USAGE\"]")
        @NotEmpty
        List<@NotNull MydataScope> scopes,

        @Schema(description = "조회 기간(개월, 1~60). 생략하면 12", example = "12", nullable = true)
        @Min(1)
        @Max(60)
        Integer lookbackMonths
) {
}
