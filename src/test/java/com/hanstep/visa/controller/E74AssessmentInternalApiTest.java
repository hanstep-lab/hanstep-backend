package com.hanstep.visa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * 채팅 도구용 E-7-4 평가 내부 API. 응답이 AI 레포 app/visa/tools.py 의 E74AssessmentToolResponse(assessmentId, reused, result)
 * 형식인지 본다. AI 계산은 가짜로 바꿔 끼움. 테스트마다 롤백.
 */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class E74AssessmentInternalApiTest {

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
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES ('test-e74-internal', 'TEST')");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = 'test-e74-internal'", Long.class);
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
    void 내부_토큰이_없으면_401() throws Exception {
        mockMvc.perform(post(URL.formatted(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asOfDate\": \"2026-11-12\"}"))
                .andExpect(status().isUnauthorized());
        verify(aiE74Client, never()).calculate(any());
    }

    @Test
    void 채팅_도구_응답_형식으로_저장하고_같은_입력은_재사용한다() throws Exception {
        String body = create(userId, "{\"asOfDate\": \"2026-11-12\", \"scenario\": null, \"legalDeclaration\": null}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reused").value(false))
                .andExpect(jsonPath("$.result.ruleVersion").value("E74-2026-v1"))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = MAPPER.readTree(body);
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("assessmentId", "reused", "result");
        long assessmentId = response.path("assessmentId").asLong();
        assertThat(assessmentId).isPositive();

        create(userId, "{\"asOfDate\": \"2026-11-12\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(true))
                .andExpect(jsonPath("$.assessmentId").value(assessmentId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM e74_assessments WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void 기준일이_없으면_400() throws Exception {
        create(userId, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("asOfDate"));
        verify(aiE74Client, never()).calculate(any());
    }

    @Test
    void 없는_사용자면_404() throws Exception {
        create(999_999_999L, "{\"asOfDate\": \"2026-11-12\"}")
                .andExpect(status().isNotFound());
        verify(aiE74Client, never()).calculate(any());
    }

    private ResultActions create(long id, String json) throws Exception {
        return mockMvc.perform(post(URL.formatted(id))
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
