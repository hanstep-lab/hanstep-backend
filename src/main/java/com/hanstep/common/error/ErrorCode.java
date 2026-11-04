package com.hanstep.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "invalid request"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "authentication required"),
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "consent required"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "resource not found"),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "duplicate request"),
    RULESET_NOT_APPROVED(HttpStatus.CONFLICT, "rule set is not approved"),
    INVALID_STATE(HttpStatus.CONFLICT, "invalid state"),
    INSUFFICIENT_DATA(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient data"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "too many requests"),
    AI_UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "ai server error"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

}