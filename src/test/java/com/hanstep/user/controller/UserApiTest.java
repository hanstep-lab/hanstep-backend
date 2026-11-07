package com.hanstep.user.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** USR-02. 실제 DB(데모 사용자 demo-e9-nguyen), 테스트마다 롤백. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    private String bearer;

    @BeforeEach
    void setUp() {
        Long userId = userRepository.findByExternalUserKey("demo-e9-nguyen").orElseThrow().getId();
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    @Test
    void 언어를_바꾸면_내_정보에_반영된다() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredLanguage\":\"vi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLanguage").value("vi"));

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.preferredLanguage").value("vi"));
    }

    @Test
    void 지원하지_않는_언어는_400() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredLanguage\":\"fr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
