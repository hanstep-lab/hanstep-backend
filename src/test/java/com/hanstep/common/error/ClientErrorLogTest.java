package com.hanstep.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.config.SecurityErrorHandler;
import com.hanstep.common.web.RequestIdFilter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 4xx·401 응답 로그 한 줄(#156). DB·스프링 컨텍스트 없이 standalone MockMvc 로 GlobalExceptionHandler 를 거치고,
 * SecurityErrorHandler(401)는 직접 부른다. 쿼리 문자열·Authorization·본문 값이 그 줄에 들어가지 않는지도 본다.
 * (기본 logback 설정이면 Spring DEBUG 로그에 쿼리가 찍힐 수 있어 "client error:" 줄만 골라 검사한다)
 */
@ExtendWith(OutputCaptureExtension.class)
class ClientErrorLogTest {

    private static final String SECRET_TOKEN = "secret-token-value";
    private static final String SECRET_QUERY = "secret-query-value";
    private static final String SECRET_BODY = "secret-body-value";

    @RestController
    static class FailingController {

        @PostMapping("/api/v1/test/state")
        void state() {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }

        @PostMapping("/api/v1/test/forbidden")
        void forbidden() {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        @PostMapping("/api/v1/test/too-large")
        void tooLarge() {
            throw new MaxUploadSizeExceededException(10L * 1024 * 1024);
        }

        @PostMapping("/api/v1/test/multipart")
        void multipart() {
            throw new MultipartException("broken multipart");
        }

        @GetMapping("/api/v1/test/boom")
        void boom() {
            throw new IllegalStateException("boom");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new RequestIdFilter())
            .build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 비즈니스_오류는_INFO_한_줄이고_쿼리_헤더_본문_값은_남기지_않는다(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/v1/test/state")
                        .queryParam("q", SECRET_QUERY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET_TOKEN)
                        .header(RequestIdFilter.HEADER, "req_test409")
                        .content(SECRET_BODY))
                .andExpect(status().isConflict());

        List<String> lines = clientErrorLines(output);
        assertThat(lines).containsExactly(
                "client error: requestId=req_test409 method=POST path=/api/v1/test/state status=409 code=INVALID_STATE");
        assertThat(String.join("\n", lines)).doesNotContain(SECRET_QUERY, SECRET_TOKEN, SECRET_BODY, "q=");
        assertThat(output.getOut().lines().filter(line -> line.contains("client error:")))
                .singleElement().asString().contains("INFO");
    }

    @Test
    void 앱의_403도_한_줄(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/v1/test/forbidden").header(RequestIdFilter.HEADER, "req_test403"))
                .andExpect(status().isForbidden());

        assertThat(clientErrorLines(output)).containsExactly(
                "client error: requestId=req_test403 method=POST path=/api/v1/test/forbidden status=403 code=FORBIDDEN");
    }

    @Test
    void 업로드_크기_초과와_깨진_multipart는_400_VALIDATION_ERROR(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/v1/test/too-large").header(RequestIdFilter.HEADER, "req_test413"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/test/multipart").header(RequestIdFilter.HEADER, "req_testmp"))
                .andExpect(status().isBadRequest());

        assertThat(clientErrorLines(output)).containsExactly(
                "client error: requestId=req_test413 method=POST path=/api/v1/test/too-large status=400 code=VALIDATION_ERROR",
                "client error: requestId=req_testmp method=POST path=/api/v1/test/multipart status=400 code=VALIDATION_ERROR");
    }

    @Test
    void 요청_id_헤더가_없으면_서버가_만든_id를_남긴다(CapturedOutput output) throws Exception {
        String requestId = mvc.perform(post("/api/v1/test/state"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);

        assertThat(requestId).startsWith("req_");
        assertThat(clientErrorLines(output)).containsExactly(
                "client error: requestId=" + requestId + " method=POST path=/api/v1/test/state status=409 code=INVALID_STATE");
    }

    @Test
    void 서버_오류_5xx는_client_error_줄이_없고_기존_ERROR는_그대로(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/test/boom")).andExpect(status().isInternalServerError());

        assertThat(clientErrorLines(output)).isEmpty();
        assertThat(output.getOut()).contains("Unexpected error");
    }

    @Test
    void 인증_실패_401도_한_줄이고_토큰_쿼리는_남기지_않는다(CapturedOutput output) throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req_test401");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.setQueryString("token=" + SECRET_QUERY);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityErrorHandler(JsonMapper.builder().build())
                .commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        List<String> lines = clientErrorLines(output);
        assertThat(lines).containsExactly(
                "client error: requestId=req_test401 method=GET path=/api/v1/users/me status=401 code=UNAUTHORIZED");
        assertThat(String.join("\n", lines)).doesNotContain(SECRET_QUERY, SECRET_TOKEN);
    }

    // 이 로그 한 줄의 "client error:" 부터 끝까지(앞의 시각·레벨·로거 이름은 설정마다 달라서 뺀다)
    private static List<String> clientErrorLines(CapturedOutput output) {
        return output.getOut().lines()
                .filter(line -> line.contains("client error:"))
                .map(line -> line.substring(line.indexOf("client error:")).strip())
                .toList();
    }
}
