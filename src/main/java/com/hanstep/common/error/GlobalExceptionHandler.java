package com.hanstep.common.error;

import com.hanstep.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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

    // 경로·쿼리 값 형식 오류 (예: 없는 stepCode)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(ErrorCode.VALIDATION_ERROR, null,
                Map.of("fields", List.of(Map.of("field", e.getName(), "reason", "invalid value"))));
    }

    // 필수 쿼리 값 누락 (예: 견적의 amount)
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        return build(ErrorCode.VALIDATION_ERROR, null,
                Map.of("fields", List.of(Map.of("field", e.getParameterName(), "reason", "required parameter"))));
    }

    // 필수 헤더 누락 (예: Idempotency-Key)
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return build(ErrorCode.VALIDATION_ERROR, null,
                Map.of("fields", List.of(Map.of("field", e.getHeaderName(), "reason", "required header"))));
    }

    // 업로드 파일이 spring.servlet.multipart 크기 제한을 넘음(등록증 업로드 DOC-01)
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return build(ErrorCode.VALIDATION_ERROR, null,
                Map.of("fields", List.of(Map.of("field", "front", "reason", "file too large"))));
    }

    // multipart 필수 파트 누락 (예: DOC-01 의 file)
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        return build(ErrorCode.VALIDATION_ERROR, null,
                Map.of("fields", List.of(Map.of("field", e.getRequestPartName(), "reason", "required part"))));
    }

    // multipart 가 아니거나 깨진 multipart 요청
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException e) {
        return build(ErrorCode.VALIDATION_ERROR, "malformed multipart request", null);
    }

    // 없는 주소
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, null, null);
    }

    // 주소는 있는데 메서드가 다름 (예: POST 전용에 GET). Allow 헤더로 받는 메서드를 알려 준다
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, null, null, e.getHeaders());
    }

    // 요청 Content-Type 을 받지 않음 (예: JSON 본문에 text/plain). Accept 헤더로 받는 형식을 알려 준다
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, null, e.getHeaders());
    }

    // 요청 Accept 로 응답할 수 없음 (예: Accept: application/xml). 오류 본문은 그래도 JSON
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return build(ErrorCode.NOT_ACCEPTABLE, null, null);
    }

    // AI 호출 실패(연결 실패·타임아웃·AI 오류 응답). 여기까지 올라오는 외부 HTTP 호출은 aiRestClient 뿐이다.
    // 환율 수집기(fx.client)는 원천 오류를 직접 잡아 FxSourceException 으로 바꾸므로 여기로 오지 않는다.
    // 다른 외부 호출이 생기면 출처를 구분해야 한다
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleAiUpstream(RestClientException e) {
        log.warn("AI upstream call failed", e);
        return build(ErrorCode.AI_UPSTREAM_ERROR, null, null);
    }

    // 그 밖의 예상 못 한 오류
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return build(ErrorCode.INTERNAL_ERROR, null, null);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, Map<String, Object> details) {
        return build(code, message, details, HttpHeaders.EMPTY);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, Map<String, Object> details,
                                                HttpHeaders headers) {
        // 4xx 는 INFO 한 줄(#156). 5xx 는 위 핸들러가 WARN·ERROR 로 남긴다
        if (code.getStatus().is4xxClientError()) {
            ClientErrorLog.info(currentRequest(), code);
        }
        // Content-Type 을 미리 정해 Accept 협상을 건너뛴다. CHT-04 처럼 Accept: text/event-stream 요청도 오류는 JSON 으로 받는다
        return ResponseEntity.status(code.getStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(code, message, details, RequestIdFilter.current()));
    }

    // 지금 처리 중인 요청(@ExceptionHandler 는 요청을 처리하던 스레드에서 돈다). 없으면 null
    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}