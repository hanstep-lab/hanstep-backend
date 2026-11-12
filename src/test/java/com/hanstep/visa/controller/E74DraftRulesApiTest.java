package com.hanstep.visa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.visa.client.AiE74Client;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시연 설정(hanstep.e74.include-draft-rules=true)이면 승인 전(DRAFT) 규칙으로 계산한 결과도 저장한다.
 * 운영 설정은 E74AssessmentApiTest. AI 계산은 가짜로 바꿔 끼움. 테스트마다 롤백.
 */
@SpringBootTest(properties = {InternalApiTestSupport.KEY_PROPERTY, "hanstep.e74.include-draft-rules=true"})
@AutoConfigureMockMvc
@Transactional
class E74DraftRulesApiTest {

    private static final String URL = "/internal/v1/ai-context/users/%d/e74-assessments";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiE74Client aiE74Client;

    private long userId;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES ('test-e74-draft', 'TEST')");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = 'test-e74-draft'", Long.class);
        jdbc.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, birth_date, current_stay_started_on, evidence_status)
                SELECT ?, id, '1996-07-21', '2022-03-07', 'VERIFIED' FROM visa_types WHERE code = 'E-9'""", userId);
        JsonNode sample;
        try (InputStream in = new ClassPathResource("e74/calculate_response.json").getInputStream()) {
            sample = MAPPER.readTree(in);
        }
        when(aiE74Client.calculate(any())).thenAnswer(invocation -> sample.deepCopy());
    }

    @Test
    void DRAFT_규칙으로_계산한_결과도_저장한다() throws Exception {
        approvalStatus("DRAFT");

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.ruleVersion").value("E74-2026-v1"));
        assertThat(assessmentCount()).isEqualTo(1);
    }

    @Test
    void RETIRED_규칙이면_409이고_저장하지_않는다() throws Exception {
        approvalStatus("RETIRED");

        create().andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("RULE_SET_NOT_APPROVED"));
        assertThat(assessmentCount()).isZero();
    }

    private void approvalStatus(String status) {
        jdbc.update("UPDATE e74_rule_sets SET approval_status = ? WHERE rule_version = 'E74-2026-v1'", status);
    }

    private ResultActions create() throws Exception {
        return mockMvc.perform(post(URL.formatted(userId))
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"asOfDate\": \"2026-11-12\"}"));
    }

    private int assessmentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM e74_assessments WHERE user_id = ?", Integer.class, userId);
    }
}
