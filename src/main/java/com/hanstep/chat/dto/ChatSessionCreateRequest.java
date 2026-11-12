package com.hanstep.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "대화방 생성(CHT-01). 본문은 생략 가능")
public record ChatSessionCreateRequest(
        @Schema(description = "제목(선택, 최대 200자). 비우면 null", nullable = true, example = "null")
        @Size(max = 200)
        String title
) {
}
