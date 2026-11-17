package com.hanstep.auth.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** AUTH-01 진입 응답 user 와 USR-01 의 shortName. 실제 DB(테스트 전용 사용자), 테스트마다 롤백. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthApiTest {

    private static final String ENTRY_URL = "/api/v1/auth/entry";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void 진입_응답_user에_displayName과_shortName이_있다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "test-auth-entry", "MARK ANTHONY REYES");

        mockMvc.perform(post(ENTRY_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryToken\":\"test-auth-entry\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.id").value(userId))
                .andExpect(jsonPath("$.user.displayName").value("MARK ANTHONY REYES"))
                .andExpect(jsonPath("$.user.shortName").value("Mark"))
                .andExpect(jsonPath("$.user.preferredLanguage").isString())
                .andExpect(jsonPath("$.user.status").value("ACTIVE"))
                .andExpect(jsonPath("$.user.onboarding.completed").isBoolean());
    }

    @Test
    void displayName이_비어_있으면_shortName은_null() throws Exception {
        TestUsers.create(jdbcTemplate, "test-auth-entry-blank", " ");

        mockMvc.perform(post(ENTRY_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryToken\":\"test-auth-entry-blank\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.shortName").value(nullValue()));
    }

    @Test
    void 내_정보에도_같은_shortName이_있다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "test-auth-me", "nguyen  van minh");

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("nguyen  van minh"))
                .andExpect(jsonPath("$.shortName").value("Nguyen"));
    }
}
