package com.hanstep.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "invalid request"),
    // 등록증 업로드(DOC-01)에 back 파트가 옴. 뒷면은 아직 지원하지 않는다(지수 합의 11/16, 확장 때 이 400 만 푼다)
    BACK_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "back side is not supported yet"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "permission required"),
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "consent required"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "resource not found"),
    RECIPIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "recipient not found"),
    FX_RATE_NOT_FOUND(HttpStatus.NOT_FOUND, "exchange rate not found"),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "duplicate request"),
    RULESET_NOT_APPROVED(HttpStatus.CONFLICT, "rule set is not approved"),
    INVALID_STATE(HttpStatus.CONFLICT, "invalid state"),
    JOB_ALREADY_PROCESSING(HttpStatus.CONFLICT, "job already processing"),
    JOB_ALREADY_FINISHED(HttpStatus.CONFLICT, "job already finished"),
    INSUFFICIENT_DATA(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient data"),
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient balance"),
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