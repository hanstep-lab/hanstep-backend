package com.hanstep.common.error;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** 메서드·Content-Type·Accept 가 맞지 않는 요청은 500 이 아니라 405·415·406 공통 오류. 실제 엔드포인트로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RequestFormatErrorApiTest {

    private static final String ENTRY_URL = "/api/v1/auth/entry";
    private static final String ROADMAP_URL = "/api/v1/visa/roadmaps";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void 인증_없는_경로에_다른_메서드면_405와_Allow() throws Exception {
        expectError(mockMvc.perform(get(ENTRY_URL)), 405, "METHOD_NOT_ALLOWED", "method not allowed")
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"));
    }

    @Test
    void 토큰이_필요한_경로에_다른_메서드면_405와_Allow() throws Exception {
        String bearer = "Bearer " + jwtTokenProvider.issue(999_999_999L);

        expectError(mockMvc.perform(get(ROADMAP_URL).header(HttpHeaders.AUTHORIZATION, bearer)),
                405, "METHOD_NOT_ALLOWED", "method not allowed")
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"));
        expectError(mockMvc.perform(delete(ROADMAP_URL + "/latest").header(HttpHeaders.AUTHORIZATION, bearer)),
                405, "METHOD_NOT_ALLOWED", "method not allowed")
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"));
    }

    @Test
    void 토큰이_없으면_메서드가_달라도_401이_먼저() throws Exception {
        expectError(mockMvc.perform(get(ROADMAP_URL)), 401, "UNAUTHORIZED", "authentication required");
    }

    @Test
    void JSON_본문에_다른_Content_Type이면_415와_Accept() throws Exception {
        expectError(mockMvc.perform(post(ENTRY_URL).contentType(MediaType.TEXT_PLAIN).content("demo")),
                415, "UNSUPPORTED_MEDIA_TYPE", "unsupported media type")
                .andExpect(header().string(HttpHeaders.ACCEPT, containsString(MediaType.APPLICATION_JSON_VALUE)));
    }

    @Test
    void JSON으로_응답할_수_없는_Accept면_406이고_오류는_JSON() throws Exception {
        jdbc.update("INSERT INTO users (external_user_key, display_name, preferred_language) VALUES (?, ?, 'en')",
                "test-accept-xml", "TEST ACCEPT XML");

        expectError(mockMvc.perform(post(ENTRY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content("{\"entryToken\": \"test-accept-xml\"}")),
                406, "NOT_ACCEPTABLE", "not acceptable");
    }

    private static ResultActions expectError(ResultActions actions, int status, String code, String message)
            throws Exception {
        return actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.requestId").exists());
    }
}
