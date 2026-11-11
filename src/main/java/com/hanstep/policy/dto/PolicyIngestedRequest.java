package com.hanstep.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** INT-13 본문. 형식은 AI(spring_client.py PolicyIngestedReport) 기준. */
public record PolicyIngestedRequest(
        @NotBlank @Size(max = 40) String documentVersion,
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String contentHash,
        @NotNull @PositiveOrZero Integer chunkCount,
        @NotBlank @Size(max = 100) String mongoCollection,
        @NotBlank String embeddingModel,
        @NotBlank String embeddingRevision,
        @Size(max = 512) String objectKey,
        @NotNull OffsetDateTime ingestedAt
) {
}
