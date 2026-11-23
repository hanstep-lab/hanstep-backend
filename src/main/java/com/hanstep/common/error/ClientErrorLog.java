package com.hanstep.common.error;

import com.hanstep.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 4xx 응답 한 줄 로그(INFO, #156). 요청이 서버에 닿았는지, 어떤 이유로 거절했는지를 requestId 로 찾으려고 남긴다
 * (hanstep-frontend#64: 업로드 실패가 서버까지 왔는지 로그로 알 수 없었음).
 * 남기는 것: requestId·메서드·경로·상태·오류 code. 남기지 않는 것: 요청 본문·쿼리 문자열·헤더(Authorization 등)·입력값·파일명.
 * GlobalExceptionHandler(4xx 응답)와 SecurityErrorHandler(401)가 같이 쓴다.
 */
public final class ClientErrorLog {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorLog.class);

    private ClientErrorLog() {
    }

    /** @param request 지금 요청. 알 수 없으면 null(메서드·경로를 "-" 로) */
    public static void info(HttpServletRequest request, ErrorCode code) {
        log.info("client error: requestId={} method={} path={} status={} code={}",
                Objects.requireNonNullElse(RequestIdFilter.current(), "-"),
                request != null ? request.getMethod() : "-",
                request != null ? request.getRequestURI() : "-",   // getRequestURI 는 쿼리 문자열을 포함하지 않는다
                code.getStatus().value(), code.name());
    }
}
