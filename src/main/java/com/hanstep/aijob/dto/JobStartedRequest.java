package com.hanstep.aijob.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** INT-08 본문. workerId·startedAt 은 로그 추적용(started_at 은 Spring 이 받은 시각으로 기록). */
public record JobStartedRequest(
        @NotBlank @Size(max = 100) String workerId,
        @NotNull OffsetDateTime startedAt
) {
}
