package com.hanstep.consent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** CNS-01 ~ 02. 실제 DB(데모 사용자 demo-e9-nguyen), 테스트마다 롤백. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConsentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        userId = userRepository.findByExternalUserKey("demo-e9-nguyen").orElseThrow().getId();
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        jdbcTemplate.update("DELETE FROM user_consents WHERE user_id = ?", userId); // 롤백되므로 실제 데이터는 그대로
    }

    @Test
    void 동의_후_철회하면_최신_상태는_REVOKED이고_이력은_남는다() throws Exception {
        save("MYDATA", true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.consents[0].status").value("GRANTED"));
        save("MYDATA", false).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/consents").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consents.length()").value(1))
                .andExpect(jsonPath("$.consents[0].consentType").value("MYDATA"))
                .andExpect(jsonPath("$.consents[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.consents[0].revokedAt").isNotEmpty());

        save("MYDATA", true);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM user_consents WHERE user_id = ? AND consent_type = 'MYDATA' ORDER BY id",
                String.class, userId)).containsExactly("REVOKED", "GRANTED");
    }

    @Test
    void 약관_버전이_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/consents").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consents\":[{\"consentType\":\"MYDATA\",\"granted\":true}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private ResultActions save(String type, boolean granted) throws Exception {
        return mockMvc.perform(post("/api/v1/consents").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"consents\":[{\"consentType\":\"" + type + "\",\"policyVersion\":\"mydata-2026-11\","
                        + "\"granted\":" + granted + "}]}"));
    }
}
