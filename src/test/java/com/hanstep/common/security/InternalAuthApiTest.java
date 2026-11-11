package com.hanstep.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 내부 API 인증(X-Internal-Token)과 요청 ID 이어받기. */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class InternalAuthApiTest {

    private static final String INTERNAL_URL = "/internal/v1/ai-context/policy-documents/search-eligibility?ids=1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get(INTERNAL_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void 토큰이_틀리면_401() throws Exception {
        mockMvc.perform(get(INTERNAL_URL).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY + "x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void JWT만_있으면_내부_API는_401() throws Exception {
        mockMvc.perform(get(INTERNAL_URL).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰이_맞으면_통과() throws Exception {
        mockMvc.perform(get(INTERNAL_URL).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void 외부_API는_내부_토큰으로_통과하지_않고_JWT로만_통과() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void 형식에_맞는_요청_ID는_이어받는다() throws Exception {
        mockMvc.perform(get(INTERNAL_URL).header("X-Request-Id", "ai-req_01:abc.DEF-9"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "ai-req_01:abc.DEF-9"))
                .andExpect(jsonPath("$.requestId").value("ai-req_01:abc.DEF-9"));
    }

    @Test
    void 형식이_틀린_요청_ID는_새로_만든다() throws Exception {
        mockMvc.perform(get(INTERNAL_URL).header("X-Request-Id", "bad id\nINJECTED"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.startsWith("req_")));
        mockMvc.perform(get(INTERNAL_URL).header("X-Request-Id", "a".repeat(65)))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.startsWith("req_")));
    }

    private String bearer() {
        Long userId = userRepository.findByExternalUserKey("demo-e9-nguyen").orElseThrow().getId();
        return "Bearer " + jwtTokenProvider.issue(userId);
    }
}
