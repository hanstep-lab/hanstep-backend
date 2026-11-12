package com.hanstep.chat.dto;

import com.hanstep.chat.domain.ChatSessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "생성된 대화방(CHT-01)")
public record ChatSessionResponse(
        @Schema(description = "대화방 ID", example = "1")
        Long id,

        @Schema(description = "제목. 없으면 null", nullable = true)
        String title,

        @Schema(description = "상태", example = "ACTIVE")
        ChatSessionStatus status,

        @Schema(description = "마지막 메시지 시각(+09:00). 메시지가 없으면 null", nullable = true)
        OffsetDateTime lastMessageAt,

        @Schema(description = "생성 시각(+09:00)", example = "2026-11-11T14:30:00+09:00")
        OffsetDateTime createdAt
) {
}
