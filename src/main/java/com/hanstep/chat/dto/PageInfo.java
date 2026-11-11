package com.hanstep.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChatPageInfo", description = "커서 페이지 정보")
public record PageInfo(
        @Schema(description = "다음 페이지 커서(불투명 문자열). 없으면 null", nullable = true, example = "MTc2MjgzNzgwMDAwMDo0Mg")
        String nextCursor,

        @Schema(description = "다음 페이지가 있으면 true", example = "false")
        boolean hasNext
) {
}
