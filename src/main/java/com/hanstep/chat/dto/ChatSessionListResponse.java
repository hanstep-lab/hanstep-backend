package com.hanstep.chat.dto;

import com.hanstep.chat.domain.ChatSessionStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "대화방 목록(CHT-02). 본인의 ACTIVE 대화방, 최근 메시지(없으면 생성 시각) 순")
public record ChatSessionListResponse(
        @ArraySchema(arraySchema = @Schema(description = "대화방. 없으면 빈 배열"))
        List<Item> sessions,

        PageInfo page
) {

    @Schema(name = "ChatSessionListItem", description = "대화방 목록 항목")
    public record Item(
            @Schema(description = "대화방 ID", example = "1")
            Long id,

            @Schema(description = "제목. 없으면 null", nullable = true)
            String title,

            @Schema(description = "상태", example = "ACTIVE")
            ChatSessionStatus status,

            @Schema(description = "마지막 메시지 시각(+09:00). 메시지가 없으면 null", nullable = true)
            OffsetDateTime lastMessageAt
    ) {
    }
}
