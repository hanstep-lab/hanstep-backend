package com.hanstep.common.error;

import java.util.Map;

public record ErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        String requestId
) {
    public static ErrorResponse of(ErrorCode errorCode, String message,
                                   Map<String, Object> details, String requestId) {
        return new ErrorResponse(
                errorCode.name(),
                message != null ? message : errorCode.getDefaultMessage(),
                details != null ? details : Map.of(),
                requestId
        );
    }
}