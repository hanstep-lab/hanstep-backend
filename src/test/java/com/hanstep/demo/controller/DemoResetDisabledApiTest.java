package com.hanstep.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.support.TestUsers;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 데모 계정 초기화가 꺼져 있을 때(기본값 hanstep.demo.reset-enabled=false). 실제 DB, 롤백. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DemoResetDisabledApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Test
    void 꺼져_있으면_데모_계정도_404이고_아무것도_지우지_않는다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "DEMO USER");
        jdbcTemplate.update("""
                INSERT INTO onboarding_progress (user_id, step_code, status, completed_at)
                VALUES (?, 'LANGUAGE', 'COMPLETED', NOW(3))""", userId);

        mockMvc.perform(post(DemoResetApiTest.URL).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + jwtTokenProvider.issue(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM onboarding_progress WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }
}
