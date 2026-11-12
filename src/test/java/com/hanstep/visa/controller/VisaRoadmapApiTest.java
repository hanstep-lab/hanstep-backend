package com.hanstep.visa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.visa.client.AiE74Client;
import com.hanstep.visa.client.AiRoadmapClient;
import com.hanstep.visa.client.AiRoadmapClient.AiCallException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 비자 로드맵 API. 테스트 사용자를 새로 넣고, AI 계산·로드맵은 가짜로 바꿔 끼운다. 두 샘플은 같은 입력에서 나온 값이라
 * 로드맵의 assessmentRequestKey 가 평가의 requestKey 와 같다(AI 레포 docs/e74/samples/calculate_response.json,
 * ai/tests/fixtures/visa_tools/roadmap_tool_response.json 의 roadmap). 운영 설정(DRAFT 규칙 불허)으로 돈다. 테스트마다 롤백.
 */
@SpringBootTest(properties = {InternalApiTestSupport.KEY_PROPERTY, "hanstep.e74.include-draft-rules=false"})
@AutoConfigureMockMvc
@Transactional
class VisaRoadmapApiTest {

    private static final String URL = "/api/v1/visa/roadmaps";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AiE74Client aiE74Client;

    @MockitoBean
    private AiRoadmapClient aiRoadmapClient;

    private long userId;
    private String bearer;
    private ObjectNode assessment;
    private ObjectNode roadmap;

    @BeforeEach
    void setUp() throws IOException {
        // 시드 규칙 E74-2026-v1 은 아직 DRAFT 라 테스트 안에서만 승인해 둔다(롤백)
        jdbc.update("UPDATE e74_rule_sets SET approval_status = 'APPROVED' WHERE rule_version = 'E74-2026-v1'");
        userId = insertUser("test-roadmap");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        assessment = sample("e74/calculate_response.json");
        roadmap = sample("visa/roadmap_response.json");
        when(aiE74Client.calculate(any())).thenAnswer(invocation -> assessment.deepCopy());
        when(aiRoadmapClient.create(any())).thenAnswer(invocation -> roadmapFor(invocation.getArgument(0)));
    }

    @Test
    void 새로_만들면_201이고_응답을_컬럼에_옮겨_저장한다() throws Exception {
        long id = roadmapId(create("{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reused").value(false))
                .andExpect(jsonPath("$.roadmap.steps.length()").value(4))
                .andExpect(jsonPath("$.roadmap.stay.stayExpiresOn").value("2027-10-04")));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT assessment_id, stay_expires_on, goal_visa_code, model_id, prompt_version FROM visa_roadmaps
                WHERE id = ? AND user_id = ?""", id, userId);
        assertThat(((Number) row.get("assessment_id")).longValue()).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM e74_assessments WHERE user_id = ?", Long.class, userId));
        assertThat(row.get("stay_expires_on").toString()).isEqualTo("2027-10-04");
        assertThat(row.get("goal_visa_code")).isEqualTo("E-7-4");
        // 템플릿 문장이면 모델·프롬프트 버전이 없다
        assertThat(row.get("model_id")).isNull();
        assertThat(row.get("prompt_version")).isNull();
        assertThat(jsonColumn(id, "warnings_json")).isEqualTo(MAPPER.readTree("[\"DRAFT_RULES\", \"RULES_EXPIRE\"]"));
        assertThat(jsonColumn(id, "result_json")).isEqualTo(roadmap);

        List<Map<String, Object>> steps = jdbc.queryForList("""
                SELECT step_order, title, description, related_rule_code, estimated_additional_score, target_date,
                       actor, required_evidence_json, status, scenario_assessment_id
                FROM visa_roadmap_steps WHERE roadmap_id = ? ORDER BY step_order""", id);
        assertThat(steps).hasSize(4);
        Map<String, Object> first = steps.get(0);
        assertThat(first.get("step_order")).isEqualTo(1);
        assertThat(first.get("title")).isEqualTo("TOPIK 4급 취득");
        assertThat(first.get("description")).isEqualTo(roadmap.path("steps").get(0).path("description").stringValue());
        assertThat(first.get("related_rule_code")).isEqualTo("KOREAN_L4_PLUS");
        assertThat(first.get("estimated_additional_score")).isEqualTo(40);
        assertThat(first.get("target_date")).isNull();
        assertThat(first.get("actor")).isEqualTo("USER");
        assertThat(MAPPER.readTree((String) first.get("required_evidence_json")))
                .isEqualTo(roadmap.path("steps").get(0).path("requiredEvidence"));
        assertThat(first.get("status")).isEqualTo("RECOMMENDED");
        assertThat(first.get("scenario_assessment_id")).isNull();
        assertThat(steps.get(1).get("actor")).isEqualTo("GOVERNMENT");
        // 신청 단계: 규칙 코드·점수 없음, 목표일은 체류 만료일
        Map<String, Object> apply = steps.get(3);
        assertThat(apply.get("related_rule_code")).isNull();
        assertThat(apply.get("estimated_additional_score")).isNull();
        assertThat(apply.get("target_date").toString()).isEqualTo("2027-10-04");
    }

    @Test
    void LLM_문장이면_모델과_프롬프트_버전을_저장한다() throws Exception {
        roadmap.set("text", MAPPER.readTree(
                "{\"source\": \"LLM\", \"modelId\": \"claude-haiku-4-5\", \"promptVersion\": \"roadmap-v1\"}"));

        long id = roadmapId(create("{}").andExpect(status().isCreated()));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT model_id, prompt_version FROM visa_roadmaps WHERE id = ?", id);
        assertThat(List.of(row.get("model_id"), row.get("prompt_version")))
                .containsExactly("claude-haiku-4-5", "roadmap-v1");
    }

    @Test
    void 같은_평가와_체류_만료일이면_200이고_AI_로드맵을_다시_부르지_않는다() throws Exception {
        long first = roadmapId(create("{}").andExpect(status().isCreated()));

        create("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(true))
                .andExpect(jsonPath("$.roadmapId").value(first))
                .andExpect(jsonPath("$.roadmap.assessmentRequestKey")
                        .value(roadmap.path("assessmentRequestKey").stringValue()));
        verify(aiRoadmapClient, times(1)).create(any());
        assertThat(roadmapCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visa_roadmap_steps WHERE roadmap_id = ?",
                Integer.class, first)).isEqualTo(4);
    }

    @Test
    void 체류_만료일이_바뀌면_같은_평가로_새_로드맵을_만든다() throws Exception {
        long first = roadmapId(create("{}").andExpect(status().isCreated()));
        // 체류기간 연장: 평가 입력(프로필)은 그대로라 평가는 재사용되지만 만료일·남은 일수·신청 목표일은 달라진다
        jdbc.update("UPDATE visa_profiles SET stay_expires_on = '2028-10-04' WHERE user_id = ?", userId);

        long second = roadmapId(create("{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roadmap.stay.stayExpiresOn").value("2028-10-04")));

        assertThat(second).isNotEqualTo(first);
        assertThat(roadmapCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM e74_assessments WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void 평가와_로드맵에_같은_프로필을_보낸다() throws Exception {
        create("""
                {"asOfDate": "2026-11-12",
                 "legalDeclaration": {"finesUnder1mCount": 0, "hasFine1mOrMore": false, "taxStayRestrictionCount": 0,
                                      "immigrationViolationCount": 0, "hasOverstay3mOrMore": false,
                                      "hasCurrentTaxArrears": false}}""")
                .andExpect(status().isCreated());

        ArgumentCaptor<JsonNode> e74 = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiE74Client).calculate(e74.capture());
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiRoadmapClient).create(sent.capture());
        JsonNode request = sent.getValue();
        // AI 로드맵 요청은 모르는 필드를 거부한다(app/visa/roadmap/schemas.py RoadmapRequest)
        assertThat(request.propertyNames()).containsExactlyInAnyOrder("asOfDate", "stayExpiresOn", "profile");
        assertThat(request.path("asOfDate").stringValue()).isEqualTo("2026-11-12");
        assertThat(request.path("stayExpiresOn").stringValue()).isEqualTo("2027-10-04");
        assertThat(request.path("profile")).isEqualTo(e74.getValue().path("profile"));
        assertThat(request.path("profile").path("legalDeclaration").propertyNames()).hasSize(6);
        assertThat(e74.getValue().path("scenario").isNull()).isTrue();
    }

    @Test
    void 체류_만료일이_없거나_지났으면_422이고_AI를_부르지_않는다() throws Exception {
        create("{\"asOfDate\": \"2027-10-05\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.details.reason").value("STAY_EXPIRED"));

        jdbc.update("UPDATE visa_profiles SET stay_expires_on = NULL WHERE user_id = ?", userId);
        create("{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("NO_STAY_EXPIRY"));

        verify(aiE74Client, never()).calculate(any());
        verify(aiRoadmapClient, never()).create(any());
    }

    @Test
    void 만료일_당일은_만들_수_있다() throws Exception {
        create("{\"asOfDate\": \"2027-10-04\"}").andExpect(status().isCreated());
    }

    @Test
    void 체류_프로필이_없으면_422() throws Exception {
        jdbc.update("DELETE FROM visa_profiles WHERE user_id = ?", userId);

        create("{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("NO_VISA_PROFILE"));
        verify(aiRoadmapClient, never()).create(any());
    }

    @Test
    void 평가에서_난_오류는_그대로_올리고_로드맵을_부르지_않는다() throws Exception {
        jdbc.update("UPDATE e74_rule_sets SET approval_status = 'DRAFT' WHERE rule_version = 'E74-2026-v1'");

        create("{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("RULE_SET_NOT_APPROVED"));
        verify(aiRoadmapClient, never()).create(any());
        assertThat(roadmapCount()).isZero();
    }

    @Test
    void 평가_키와_로드맵_키가_다르면_502이고_저장하지_않는다() throws Exception {
        roadmap.put("assessmentRequestKey", "0000000000000000000000000000000000000000000000000000000000000000");

        create("{}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"));
        assertThat(roadmapCount()).isZero();
    }

    @Test
    void 테이블에_넣을_수_없는_로드맵이면_502이고_저장하지_않는다() throws Exception {
        ObjectNode step = (ObjectNode) roadmap.path("steps").get(0);
        step.put("actor", "BANK");
        create("{}").andExpect(status().isBadGateway());

        step.put("actor", "USER");
        step.put("title", "가".repeat(201));
        create("{}").andExpect(status().isBadGateway());

        step.put("title", "TOPIK 4급 취득");
        step.put("order", 2);
        create("{}").andExpect(status().isBadGateway());
        assertThat(roadmapCount()).isZero();
    }

    @Test
    void AI_로드맵_호출이_실패하면_502이고_저장하지_않는다() throws Exception {
        doThrow(new AiCallException("AI_UNREACHABLE", null)).when(aiRoadmapClient).create(any());

        create("{}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"));
        assertThat(roadmapCount()).isZero();
    }

    @Test
    void 동시에_저장되면_먼저_저장된_로드맵을_돌려준다() throws Exception {
        // AI 를 기다리는 사이 같은 평가·만료일의 다른 요청이 먼저 저장한 상황
        doAnswer(invocation -> {
            long assessmentId = jdbc.queryForObject("SELECT id FROM e74_assessments WHERE user_id = ?", Long.class,
                    userId);
            jdbc.update("""
                    INSERT INTO visa_roadmaps (user_id, assessment_id, stay_expires_on, result_json)
                    VALUES (?, ?, '2027-10-04', ?)""", userId, assessmentId, MAPPER.writeValueAsString(roadmap));
            return roadmapFor(invocation.getArgument(0));
        }).when(aiRoadmapClient).create(any());

        create("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(true))
                .andExpect(jsonPath("$.roadmapId").value(jdbc.queryForObject(
                        "SELECT id FROM visa_roadmaps WHERE user_id = ?", Long.class, userId)));
        assertThat(roadmapCount()).isEqualTo(1);
    }

    @Test
    void 최신_로드맵과_1건_조회는_저장된_결과를_그대로_준다() throws Exception {
        mockMvc.perform(get(URL + "/latest").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
        long id = roadmapId(create("{}").andExpect(status().isCreated()));

        mockMvc.perform(get(URL + "/latest").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roadmapId").value(id))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.roadmap.steps.length()").value(4));
        mockMvc.perform(get(URL + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roadmap.stay.daysToExpiry").value(roadmap.path("stay").path("daysToExpiry")
                        .intValue()));
        verify(aiRoadmapClient, times(1)).create(any());

        long otherId = insertUser("test-roadmap-other");
        mockMvc.perform(get(URL + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(otherId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verify(aiRoadmapClient, never()).create(any());
    }

    // AI 로드맵처럼 보낸 체류 만료일을 응답 stay 에 그대로 담는다
    private JsonNode roadmapFor(JsonNode request) {
        ObjectNode response = roadmap.deepCopy();
        ((ObjectNode) response.path("stay")).put("stayExpiresOn", request.path("stayExpiresOn").stringValue());
        ArrayNode steps = (ArrayNode) response.path("steps");
        ((ObjectNode) steps.get(steps.size() - 1)).put("targetDate", request.path("stayExpiresOn").stringValue());
        return response;
    }

    private ResultActions create(String json) throws Exception {
        return mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static long roadmapId(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString()).path("roadmapId").asLong();
    }

    private JsonNode jsonColumn(long roadmapId, String column) {
        return MAPPER.readTree(jdbc.queryForObject(
                "SELECT " + column + " FROM visa_roadmaps WHERE id = ?", String.class, roadmapId));
    }

    private int roadmapCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM visa_roadmaps WHERE user_id = ?", Integer.class, userId);
    }

    private static ObjectNode sample(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return (ObjectNode) MAPPER.readTree(in);
        }
    }

    // AI 를 가짜로 바꿔 끼우므로 프로필은 체류 정보만 넣는다(만료일은 로드맵 샘플과 같은 날)
    private long insertUser(String key) {
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES (?, 'TEST ROADMAP')", key);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class, key);
        jdbc.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, birth_date, current_stay_started_on,
                                           evidence_status)
                SELECT ?, id, '2027-10-04', '1994-03-12', '2021-04-01', 'VERIFIED' FROM visa_types WHERE code = 'E-9'""",
                id);
        return id;
    }
}
