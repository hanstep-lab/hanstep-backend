package com.hanstep.aijob.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** INT-08 본문. workerId 는 로그 추적용(테이블에 칸 없음). */
public record JobStartedRequest(
        @NotBlank @Size(max = 100) String workerId,
        @NotNull OffsetDateTime startedAt
) {
}
