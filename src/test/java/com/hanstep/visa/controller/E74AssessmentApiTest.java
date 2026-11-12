package com.hanstep.visa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.visa.client.AiE74Client;
import com.hanstep.visa.client.AiE74Client.AiCallException;
import com.hanstep.visa.client.AiE74Client.AiValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * E-7-4 평가 API. 테스트 사용자를 새로 넣고, AI 계산은 가짜로 바꿔 끼운다
 * (응답은 AI 레포 docs/e74/samples/calculate_response.json, ELIGIBLE 305점·항목 28개). 테스트마다 롤백.
 */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class E74AssessmentApiTest {

    private static final String URL = "/api/v1/visa/e74/assessments";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AiE74Client aiE74Client;

    private long userId;
    private String bearer;
    private ObjectNode sample;

    @BeforeEach
    void setUp() throws IOException {
        userId = insertUserWithProfile("test-e74");
        bearer = bearer(userId);
        try (InputStream in = new ClassPathResource("e74/calculate_response.json").getInputStream()) {
            sample = (ObjectNode) MAPPER.readTree(in);
        }
        when(aiE74Client.calculate(any())).thenAnswer(invocation -> sample.deepCopy());
    }

    @Test
    void 새로_계산하면_201이고_응답을_컬럼에_옮겨_저장한다() throws Exception {
        long id = assessmentId(create("{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reused").value(false))
                .andExpect(jsonPath("$.result.status").value("ELIGIBLE"))
                .andExpect(jsonPath("$.result.score.total").value(305)));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT a.request_key, a.as_of_date, a.is_scenario, a.scenario_json, a.base_score, a.bonus_score,
                       a.penalty_score, a.total_score, a.required_score, a.score_requirement_met, a.eligibility_status,
                       a.potential_total_score, s.rule_version
                FROM e74_assessments a JOIN e74_rule_sets s ON s.id = a.rule_set_id
                WHERE a.id = ? AND a.user_id = ?""", id, userId);
        assertThat(row.get("rule_version")).isEqualTo("E74-2026-v1");
        assertThat(row.get("request_key")).isEqualTo(sample.path("requestKey").stringValue());
        assertThat(row.get("as_of_date").toString()).isEqualTo(LocalDate.now(KstDates.KST).toString());
        assertThat(row.get("is_scenario")).isEqualTo(false);
        assertThat(row.get("scenario_json")).isNull();
        assertThat(List.of(row.get("base_score"), row.get("bonus_score"), row.get("penalty_score"),
                row.get("total_score"), row.get("required_score"))).containsExactly(205, 100, 0, 305, 200);
        assertThat(row.get("score_requirement_met")).isEqualTo(true);
        assertThat(row.get("eligibility_status")).isEqualTo("ELIGIBLE");
        assertThat(row.get("potential_total_score")).isEqualTo(305);

        assertThat(jsonColumn(id, "result_json")).isEqualTo(sample);
        assertThat(jsonColumn(id, "missing_inputs_json")).isEqualTo(sample.path("missingInputs"));
        assertThat(jsonColumn(id, "needs_evidence_json")).isEqualTo(sample.path("needsEvidence"));
        JsonNode snapshot = jsonColumn(id, "input_snapshot");
        assertThat(snapshot.path("profile").path("visaProfile").path("visaTypeCode").stringValue()).isEqualTo("E-9");
        assertThat(snapshot.path("profile").path("legalDeclaration").isNull()).isTrue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM e74_assessment_items WHERE assessment_id = ?",
                Integer.class, id)).isEqualTo(sample.path("items").size());
        Map<String, Object> item = jdbc.queryForMap("""
                SELECT category, status, earned_points, potential_points, message_code, evidence_json
                FROM e74_assessment_items WHERE assessment_id = ? AND rule_code = 'INCOME_30_35'""", id);
        assertThat(item.get("category")).isEqualTo("BASE_INCOME");
        assertThat(item.get("status")).isEqualTo("MET");
        assertThat(List.of(item.get("earned_points"), item.get("potential_points"))).containsExactly(65, 65);
        assertThat(item.get("message_code")).isEqualTo("E74_INCOME_BAND");
        assertThat(MAPPER.readTree((String) item.get("evidence_json")))
                .isEqualTo(sample.path("items").get(0).path("evidence"));
    }

    @Test
    void 같은_입력이면_200이고_새로_저장하지_않는다() throws Exception {
        long first = assessmentId(create("{}").andExpect(status().isCreated()));

        create("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(true))
                .andExpect(jsonPath("$.assessmentId").value(first))
                .andExpect(jsonPath("$.result.requestKey").value(sample.path("requestKey").stringValue()));
        assertThat(assessmentCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM e74_assessment_items WHERE assessment_id = ?",
                Integer.class, first)).isEqualTo(sample.path("items").size());
    }

    @Test
    void ruleCode가_없는_항목은_category로_저장한다() throws Exception {
        // 입력이 부족하면 기본항목(소득·한국어)이 ruleCode 없이 온다
        ((ObjectNode) sample.path("items").get(0)).putNull("ruleCode");

        long id = assessmentId(create("{}").andExpect(status().isCreated()));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM e74_assessment_items
                WHERE assessment_id = ? AND rule_code = 'BASE_INCOME' AND category = 'BASE_INCOME'""",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM e74_assessment_items WHERE assessment_id = ?",
                Integer.class, id)).isEqualTo(sample.path("items").size());
    }

    @Test
    void 기준일에_맞는_규칙_버전이_없으면_409이고_저장하지_않는다() throws Exception {
        sample.putNull("ruleVersion");

        create("{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.details.reason").value("NO_APPLICABLE_RULE_SET"));
        assertThat(assessmentCount()).isZero();
    }

    @Test
    void 등록되지_않은_규칙_버전이면_409이고_저장하지_않는다() throws Exception {
        sample.put("ruleVersion", "E74-2099-v1");

        create("{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("RULE_SET_NOT_REGISTERED"))
                .andExpect(jsonPath("$.details.ruleVersion").value("E74-2099-v1"));
        assertThat(assessmentCount()).isZero();
    }

    @Test
    void AI_호출이_실패하면_502이고_저장하지_않는다() throws Exception {
        when(aiE74Client.calculate(any())).thenThrow(new AiCallException("AI_UNREACHABLE", null));

        create("{}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"));
        assertThat(assessmentCount()).isZero();
    }

    @Test
    void AI가_보낸_값을_거부하면_400과_필드_경로를_준다() throws Exception {
        when(aiE74Client.calculate(any())).thenThrow(new AiValidationException(
                List.of(Map.of("field", "scenario.addEvidences.0.evidenceType", "reason", "enum"),
                        Map.of("field", "profile.legalDeclaration.finesUnder1mCount", "reason", "less_than_equal")),
                null));

        create("{\"scenario\": {\"addEvidences\": [{\"evidenceType\": \"UNKNOWN\"}]}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("scenario.addEvidences.0.evidenceType"))
                .andExpect(jsonPath("$.details.fields[1].field").value("profile.legalDeclaration.finesUnder1mCount"));
        assertThat(assessmentCount()).isZero();
    }

    @Test
    void AI가_저장된_프로필을_거부하면_422와_필드_경로를_준다() throws Exception {
        // 예: 이직 뒤 예전 근무 이력을 닫지 않아 현재 근무처(ended_on NULL)가 둘. 요청을 고쳐도 해결되지 않는다
        when(aiE74Client.calculate(any())).thenThrow(new AiValidationException(
                List.of(Map.of("field", "profile", "reason", "at most one current employment (endedOn null) is allowed")),
                null));

        create("{\"scenario\": {\"addEvidences\": [{\"evidenceType\": \"TOPIK\", \"levelValue\": \"4\"}]}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.details.reason").value("PROFILE_REJECTED"))
                .andExpect(jsonPath("$.details.fields[0].field").value("profile"));
        assertThat(assessmentCount()).isZero();
    }

    @Test
    void 체류_프로필이_없으면_422이고_AI를_부르지_않는다() throws Exception {
        jdbc.update("DELETE FROM visa_profiles WHERE user_id = ?", userId);

        create("{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.details.reason").value("NO_VISA_PROFILE"));
        verify(aiE74Client, never()).calculate(any());
    }

    @Test
    void MySQL_프로필을_AI_입력_형식으로_보낸다() throws Exception {
        create("""
                {"asOfDate": "2026-11-12",
                 "legalDeclaration": {"finesUnder1mCount": 1, "hasFine1mOrMore": false, "taxStayRestrictionCount": 0,
                                      "immigrationViolationCount": 0, "hasOverstay3mOrMore": false,
                                      "hasCurrentTaxArrears": false}}""")
                .andExpect(status().isCreated());

        JsonNode request = sentRequest();
        assertThat(request.propertyNames()).containsExactlyInAnyOrder("asOfDate", "profile", "scenario");
        assertThat(request.path("asOfDate").stringValue()).isEqualTo("2026-11-12");
        assertThat(request.path("scenario").isNull()).isTrue();

        // AI 는 모르는 필드를 거부하므로 키 이름까지 맞춘다(app/visa/e74/schemas.py)
        JsonNode profile = request.path("profile");
        assertThat(profile.propertyNames()).containsExactlyInAnyOrder(
                "visaProfile", "employmentHistories", "incomeRecords", "visaEvidences", "legalDeclaration");
        JsonNode visaProfile = profile.path("visaProfile");
        assertThat(visaProfile.propertyNames()).containsExactlyInAnyOrder(
                "visaTypeCode", "birthDate", "evidenceStatus", "currentStayStartedOn", "priorStayMonths");
        assertThat(visaProfile.path("visaTypeCode").stringValue()).isEqualTo("E-9");
        assertThat(visaProfile.path("birthDate").stringValue()).isEqualTo("1996-07-21");
        assertThat(visaProfile.path("currentStayStartedOn").stringValue()).isEqualTo("2022-03-07");
        assertThat(visaProfile.path("priorStayMonths").intValue()).isZero();

        JsonNode employment = profile.path("employmentHistories").get(0);
        assertThat(employment.propertyNames()).containsExactlyInAnyOrder("employmentHistoryId", "employerName",
                "industryCode", "workplaceRegionCode", "isDepopulationArea", "startedOn", "endedOn", "contractEndOn",
                "contractAnnualSalary", "leftWithoutFault", "verificationStatus");
        assertThat(employment.path("employmentHistoryId").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM employment_histories WHERE user_id = ?", Long.class, userId));
        assertThat(employment.path("contractAnnualSalary").isIntegralNumber()).isTrue();
        assertThat(employment.path("contractAnnualSalary").asLong()).isEqualTo(31_500_000L);
        assertThat(employment.path("endedOn").isNull()).isTrue();
        assertThat(employment.path("leftWithoutFault").booleanValue()).isTrue();

        JsonNode incomes = profile.path("incomeRecords");
        assertThat(incomes.get(0).propertyNames()).containsExactlyInAnyOrder(
                "incomeYear", "annualAmount", "source", "verificationStatus");
        assertThat(List.of(incomes.get(0).path("incomeYear").intValue(), incomes.get(1).path("incomeYear").intValue()))
                .containsExactly(2024, 2025);
        assertThat(incomes.get(0).path("annualAmount").isIntegralNumber()).isTrue();

        JsonNode evidences = profile.path("visaEvidences");
        assertThat(evidences.size()).isEqualTo(3);
        assertThat(evidences.get(0).propertyNames()).containsExactlyInAnyOrder("visaEvidenceId", "evidenceType",
                "levelValue", "issuedOn", "expiresOn", "status", "preTestScore", "preTestAssignedStage",
                "relatedToCurrentJob");
        assertThat(evidences.get(1).path("evidenceType").stringValue()).isEqualTo("KIIP_PRE_TEST");
        assertThat(List.of(evidences.get(1).path("preTestScore").intValue(),
                evidences.get(1).path("preTestAssignedStage").intValue())).containsExactly(70, 3);
        assertThat(evidences.get(2).path("relatedToCurrentJob").booleanValue()).isTrue();

        JsonNode legal = profile.path("legalDeclaration");
        assertThat(legal.propertyNames()).hasSize(6);
        assertThat(legal.path("finesUnder1mCount").intValue()).isEqualTo(1);
    }

    @Test
    void 본문이_없으면_오늘_기준으로_계산한다() throws Exception {
        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isCreated());

        assertThat(sentRequest().path("asOfDate").stringValue()).isEqualTo(LocalDate.now(KstDates.KST).toString());
    }

    @Test
    void 감점_횟수가_범위_밖이거나_시나리오가_객체가_아니면_400() throws Exception {
        create("{\"legalDeclaration\": {\"finesUnder1mCount\": 100}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("legalDeclaration.finesUnder1mCount"));
        create("{\"scenario\": [1, 2]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("scenario"));
        verify(aiE74Client, never()).calculate(any());
    }

    @Test
    void 최신_평가는_저장된_결과를_그대로_준다() throws Exception {
        mockMvc.perform(get(URL + "/latest").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
        long id = assessmentId(create("{}").andExpect(status().isCreated()));

        mockMvc.perform(get(URL + "/latest").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentId").value(id))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.result.status").value("ELIGIBLE"))
                .andExpect(jsonPath("$.result.items.length()").value(sample.path("items").size()));
        mockMvc.perform(get(URL + "/latest").param("scenario", "true").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void 다른_사용자의_평가는_404() throws Exception {
        long otherId = insertUserWithProfile("test-e74-other");
        long id = assessmentId(mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer(otherId))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isCreated()));

        mockMvc.perform(get(URL + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(URL + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(otherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentId").value(id));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verify(aiE74Client, never()).calculate(any());
    }

    private ResultActions create(String json) throws Exception {
        return mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private JsonNode sentRequest() {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiE74Client).calculate(captor.capture());
        return captor.getValue();
    }

    private static long assessmentId(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString()).path("assessmentId").asLong();
    }

    private JsonNode jsonColumn(long assessmentId, String column) {
        return MAPPER.readTree(jdbc.queryForObject(
                "SELECT " + column + " FROM e74_assessments WHERE id = ?", String.class, assessmentId));
    }

    private int assessmentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM e74_assessments WHERE user_id = ?", Integer.class, userId);
    }

    private String bearer(long id) {
        return "Bearer " + jwtTokenProvider.issue(id);
    }

    // 필리핀 E-9 제조업 근로자(합성). 소득은 연도 역순으로 넣어 스냅샷이 연도순인지 본다
    private long insertUserWithProfile(String key) {
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES (?, 'TEST E74')", key);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class, key);
        jdbc.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, nationality_code, birth_date,
                                           current_stay_started_on, prior_stay_months, evidence_status)
                SELECT ?, id, '2027-01-06', 'PHL', '1996-07-21', '2022-03-07', 0, 'VERIFIED'
                FROM visa_types WHERE code = 'E-9'""", id);
        jdbc.update("""
                INSERT INTO employment_histories (user_id, employer_name, industry_code, workplace_region_code,
                    is_depopulation_area, started_on, contract_end_on, contract_annual_salary, left_without_fault,
                    verification_status)
                VALUES (?, 'TEST-METAL', 'MANUFACTURING', '48250', FALSE, '2022-03-14', '2029-03-13', 31500000.00,
                        TRUE, 'VERIFIED')""", id);
        jdbc.update("""
                INSERT INTO income_records (user_id, income_year, annual_amount, source, verification_status)
                VALUES (?, 2025, 32100000.00, 'DOCUMENT', 'VERIFIED'), (?, 2024, 30400000.00, 'DOCUMENT', 'VERIFIED')""",
                id, id);
        jdbc.update("""
                INSERT INTO visa_evidence (user_id, evidence_type, level_value, issued_on, expires_on, status)
                VALUES (?, 'TOPIK', '3', '2026-04-12', '2028-04-12', 'VERIFIED')""", id);
        jdbc.update("""
                INSERT INTO visa_evidence (user_id, evidence_type, pre_test_score, pre_test_assigned_stage, status)
                VALUES (?, 'KIIP_PRE_TEST', 70, 3, 'DECLARED')""", id);
        jdbc.update("""
                INSERT INTO visa_evidence (user_id, evidence_type, level_value, related_to_current_job, status)
                VALUES (?, 'NATIONAL_CERTIFICATE', '용접기능사', TRUE, 'VERIFIED')""", id);
        return id;
    }
}
