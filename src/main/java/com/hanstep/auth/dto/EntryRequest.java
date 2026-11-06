package com.hanstep.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "SOL Global 에서 넘어올 때 받은 진입 토큰")
public record EntryRequest(
        @Schema(description = "진입 토큰. 데모에서는 users.external_user_key 값", example = "demo-e9-nguyen")
        @NotBlank
        @Size(max = 64)
        String entryToken
) {
}
