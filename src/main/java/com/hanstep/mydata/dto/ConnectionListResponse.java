package com.hanstep.mydata.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "마이데이터 연결 상태. MYD-02 응답도 같은 형식")
public record ConnectionListResponse(
        @Schema(description = "CONNECTED 기관이 하나라도 있으면 true", example = "true")
        boolean connected,

        @ArraySchema(arraySchema = @Schema(description = "기관별 연동. 연결한 적 없으면 빈 배열"))
        List<ConnectionResponse> connections
) {
}
