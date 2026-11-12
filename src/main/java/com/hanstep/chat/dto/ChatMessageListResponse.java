package com.hanstep.chat.dto;

import com.hanstep.chat.domain.ChatMessageStatus;
import com.hanstep.chat.domain.ChatRole;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

@Schema(description = "메시지 목록(CHT-03). 최신 size 개를 오래된 순으로. nextCursor 로 그 이전 메시지를 받는다")
public record ChatMessageListResponse(
        @ArraySchema(arraySchema = @Schema(description = "메시지(오래된 순). 없으면 빈 배열"))
        List<Message> messages,

        PageInfo page
) {

    @Schema(name = "ChatMessage", description = "메시지")
    public record Message(
            @Schema(description = "메시지 ID", example = "11")
            Long id,

            @Schema(description = "발화자", example = "ASSISTANT")
            ChatRole role,

            @Schema(description = "본문(식별번호는 마스킹되어 저장됨). 생성 중(STREAMING)이면 빈 문자열",
                    example = "This month you can save about 675,317 KRW.")
            String content,

            @Schema(description = "구조화 카드·버튼(AI 가 준 JSON 그대로). 없으면 null", nullable = true)
            JsonNode blocks,

            @Schema(description = "COMPLETED·STREAMING·PARTIAL(앱 연결 끊김으로 중단)·FAILED", example = "COMPLETED")
            ChatMessageStatus status,

            @Schema(description = "앱이 보낸 메시지 ID(USER 만, ASSISTANT 는 null)", nullable = true,
                    example = "c0a8012e-7f3b-4d1a-9c55-2b6f0e3d9a10")
            String clientMessageId,

            @Schema(description = "실행 ID(USER·ASSISTANT 쌍이 같은 값)", nullable = true, example = "run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59")
            String runId,

            @Schema(description = "응답 대상 USER 메시지 ID(ASSISTANT 만)", nullable = true, example = "10")
            Long replyToId,

            @ArraySchema(arraySchema = @Schema(description = "답변 근거. 없으면 빈 배열"))
            List<ChatSource> sources,

            @Schema(description = "요청 수신부터 답변 완료까지 ms(ASSISTANT COMPLETED 만)", nullable = true, example = "4210")
            Integer latencyMs,

            @Schema(description = "생성 시각(+09:00)", example = "2026-11-11T14:30:00+09:00")
            OffsetDateTime createdAt
    ) {
    }
}
