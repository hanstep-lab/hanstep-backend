package com.hanstep.auth.dto;

import com.hanstep.user.dto.UserMeResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "진입 결과. 이후 요청에 Authorization: Bearer {accessToken}")
public record EntryResponse(
        @Schema(description = "JWT", example = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJoYW5zdGVwIiwic3ViIjoiMSJ9.signature")
        String accessToken,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(description = "유효 시간(초). 만료되면 /auth/entry 를 다시 호출", example = "3600")
        long expiresIn,

        UserMeResponse user
) {
}
