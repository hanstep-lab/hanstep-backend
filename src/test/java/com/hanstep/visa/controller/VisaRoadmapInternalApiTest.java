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
import com.hanstep.visa.client.AiRoadmapClient;
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
 * 채팅 도구용 비자 로드맵 내부 API. 응답이 AI 레포 app/visa/tools.py 의 RoadmapToolResponse(roadmapId, roadmap) 형식인지
 * 본다(reused 는 AI 가 무시). AI 계산·로드맵은 가짜로 바꿔 끼움. 운영 설정(DRAFT 규칙 불허)으로 돈다. 테스트마다 롤백.
 */
@SpringBootTest(properties = {InternalApiTestSupport.KEY_PROPERTY, "hanstep.e74.include-draft-rules=false"})
@AutoConfigureMockMvc
@Transactional
class VisaRoadmapInternalApiTest {

    private static final String URL = "/internal/v1/ai-context/users/%d/visa-roadmaps";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiE74Client aiE74Client;

    @MockitoBean
    private AiRoadmapClient aiRoadmapClient;

    private long userId;

    @BeforeEach
    void setUp() throws IOException {
        // 시드 규칙 E74-2026-v1 은 아직 DRAFT 라 테스트 안에서만 승인해 둔다(롤백)
        jdbc.update("UPDATE e74_rule_sets SET approval_status = 'APPROVED' WHERE rule_version = 'E74-2026-v1'");
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES ('test-roadmap-internal', 'TEST')");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = 'test-roadmap-internal'",
                Long.class);
        // 만료일은 로드맵 샘플(stay.stayExpiresOn)과 같은 날
        jdbc.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, birth_date, current_stay_started_on,
                                           evidence_status)
                SELECT ?, id, '2027-10-04', '1994-03-12', '2021-04-01', 'VERIFIED' FROM visa_types WHERE code = 'E-9'""",
                userId);
        JsonNode assessment = sample("e74/calculate_response.json");
        JsonNode roadmap = sample("visa/roadmap_response.json");
        when(aiE74Client.calculate(any())).thenAnswer(invocation -> assessment.deepCopy());
        when(aiRoadmapClient.create(any())).thenAnswer(invocation -> roadmap.deepCopy());
    }

    @Test
    void 내부_토큰이_없으면_401() throws Exception {
        mockMvc.perform(post(URL.formatted(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asOfDate\": \"2026-11-12\"}"))
                .andExpect(status().isUnauthorized());
        verify(aiRoadmapClient, never()).create(any());
    }

    @Test
    void 채팅_도구_응답_형식으로_저장하고_같은_입력은_재사용한다() throws Exception {
        String body = create(userId, "{\"asOfDate\": \"2026-11-12\", \"legalDeclaration\": null}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reused").value(false))
                .andExpect(jsonPath("$.roadmap.goalVisaCode").value("E-7-4"))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = MAPPER.readTree(body);
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("roadmapId", "reused", "roadmap");
        long roadmapId = response.path("roadmapId").asLong();
        assertThat(roadmapId).isPositive();

        create(userId, "{\"asOfDate\": \"2026-11-12\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(true))
                .andExpect(jsonPath("$.roadmapId").value(roadmapId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visa_roadmaps WHERE user_id = ?",
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

    private static JsonNode sample(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return MAPPER.readTree(in);
        }
    }
}
