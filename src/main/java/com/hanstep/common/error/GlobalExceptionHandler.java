package com.hanstep.common.error;

import com.hanstep.common.web.RequestIdFilter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 우리가 직접 던진 오류
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        return build(e.getErrorCode(), e.getMessage(), e.getDetails());
    }

    // @Valid 검사 실패 (필드별 이유를 details.fields에 담음)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of(
                        "field", f.getField(),
                        "reason", f.getDefaultMessage() != null ? f.getDefaultMessage() : "invalid"))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, null, Map.of("fields", fields));
    }

    // JSON 형식이 깨진 요청
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return build(ErrorCode.VALIDATION_ERROR, "malformed request body", null);
    }

    // 없는 주소
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, null, null);
    }

    // 그 밖의 예상 못 한 오류
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return build(ErrorCode.INTERNAL_ERROR, null, null);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, Map<String, Object> details) {
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, message, details, RequestIdFilter.current()));
    }
}