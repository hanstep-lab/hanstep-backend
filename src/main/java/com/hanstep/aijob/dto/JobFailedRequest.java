package com.hanstep.aijob.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * INT-10 본문. errorCode 예(정책 적재): SOURCE_FETCH_FAILED, HASH_MISMATCH, EXTRACTION_FAILED, NEEDS_OCR,
 * NEEDS_CONVERSION, VERSION_CONFLICT, EMBEDDING_FAILED, VECTOR_STORE_FAILED. 작업 종류마다 달라 목록으로 막지 않는다.
 * retryable 은 기록만 하고 재시도 정책은 아직 없다. message 500자 제한은 코드포인트 기준이라 서비스에서 검사한다.
 */
public record JobFailedRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String errorCode,
        @NotNull String message,
        @NotNull Boolean retryable
) {
}
