package com.hanstep.user.dto;

import com.hanstep.user.domain.Language;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "화면·답변 언어 변경")
public record LanguageUpdateRequest(
        @Schema(description = "바꿀 언어", example = "vi", allowableValues = {"en", "ko", "vi", "zh"})
        @NotNull
        Language preferredLanguage
) {
}
