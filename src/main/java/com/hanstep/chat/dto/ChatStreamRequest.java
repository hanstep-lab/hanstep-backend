package com.hanstep.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "메시지 보내기(CHT-04)")
public record ChatStreamRequest(
        @Schema(description = "앱이 만든 메시지 ID(1~64자, UUID 권장). 같은 대화방에서 같은 값이면 409",
                example = "c0a8012e-7f3b-4d1a-9c55-2b6f0e3d9a10")
        @NotBlank
        @Size(max = 64)
        String clientMessageId,

        @Schema(description = "질문(1~2000자, 공백만은 안 됨). 식별번호는 마스킹해서 저장·전달",
                example = "How much can I save this month?")
        @NotBlank
        @Size(max = 2000)
        String content
) {
}
