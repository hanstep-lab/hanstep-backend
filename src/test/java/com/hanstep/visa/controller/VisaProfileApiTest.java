package com.hanstep.visa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.support.TestUsers;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 체류 정보 직접 저장(PUT /api/v1/visa/profile, #131). 실제 DB, 테스트마다 롤백. 값은 가상 인물 합성 데이터. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VisaProfileApiTest {

    static final String URL = "/api/v1/visa/profile";
    static final String VALID = """
            {"fields": [
              {"fieldKey": "nationality", "value": "PHL"},
              {"fieldKey": "birthDate", "value": "1995-08-15"},
              {"fieldKey": "visaType", "value": " E-9 "},
              {"fieldKey": "stayExpiry", "value": "2027-03-01"}
            ]}""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    long userId;
    String bearer;

    @BeforeEach
    void setUp() {
        userId = TestUsers.create(jdbcTemplate, "test-visa-profile-" + UUID.randomUUID(), "MARK ANTHONY REYES");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    @Test
    void 저장하면_4개_항목과_daysRemaining을_주고_DECLARED로_넣는다() throws Exception {
        long expected = ChronoUnit.DAYS.between(LocalDate.now(KstDates.KST), LocalDate.parse("2027-03-01"));

        save(VALID).andExpect(status().isOk())
                .andExpect(jsonPath("$.nationality").value("PHL"))
                .andExpect(jsonPath("$.birthDate").value("1995-08-15"))
                .andExpect(jsonPath("$.visaType").value("E-9"))
                .andExpect(jsonPath("$.stayExpiry").value("2027-03-01"))
                .andExpect(jsonPath("$.daysRemaining").value(expected))
                .andExpect(jsonPath("$.evidenceStatus").value("DECLARED"));

        var profile = profile();
        assertThat(profile.get("code")).isEqualTo("E-9");
        assertThat(profile.get("nationality_code")).isEqualTo("PHL");
        assertThat(profile.get("birth_date").toString()).isEqualTo("1995-08-15");
        assertThat(profile.get("stay_expires_on").toString()).isEqualTo("2027-03-01");
        assertThat(profile.get("source_document_id")).isNull();
        assertThat(profile.get("evidence_status")).isEqualTo("DECLARED");
        assertThat(profile.get("current_stay_started_on")).isNull();
    }

    @Test
    void 이미_있는_프로필은_4개만_바꾸고_입국일과_이전_체류_개월은_그대로_둔다() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO documents (user_id, document_type, side, content_type, file_size_bytes, sha256, status,
                  retention_until, confirmed_at)
                VALUES (?, 'ARC', 'FRONT', 'image/jpeg', 1, ?, 'CONFIRMED', NOW(3), NOW(3))""",
                userId, "a".repeat(64));
        long documentId = jdbcTemplate.queryForObject("SELECT id FROM documents WHERE user_id = ?", Long.class, userId);
        jdbcTemplate.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, nationality_code, birth_date,
                  current_stay_started_on, prior_stay_months, source_document_id, evidence_status)
                VALUES (?, (SELECT id FROM visa_types WHERE code = 'H-2'), '2026-12-31', 'VNM', '1990-01-01',
                  '2023-02-01', 12, ?, 'VERIFIED')""", userId, documentId);

        save(VALID).andExpect(status().isOk());

        var profile = profile();
        assertThat(profile.get("code")).isEqualTo("E-9");
        assertThat(profile.get("nationality_code")).isEqualTo("PHL");
        assertThat(profile.get("birth_date").toString()).isEqualTo("1995-08-15");
        assertThat(profile.get("stay_expires_on").toString()).isEqualTo("2027-03-01");
        assertThat(profile.get("current_stay_started_on").toString()).isEqualTo("2023-02-01");
        assertThat(((Number) profile.get("prior_stay_months")).intValue()).isEqualTo(12);
        assertThat(profile.get("source_document_id")).isNull();
        assertThat(profile.get("evidence_status")).isEqualTo("DECLARED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM visa_profiles WHERE user_id = ?", Integer.class,
                userId)).isEqualTo(1);
    }

    @Test
    void 저장하면_온보딩_ID_DOCUMENT_단계가_COMPLETED() throws Exception {
        save(VALID).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM onboarding_progress WHERE user_id = ? AND step_code = 'ID_DOCUMENT'""",
                String.class, userId)).isEqualTo("COMPLETED");
        mockMvc.perform(get("/api/v1/onboarding").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[1].stepCode").value("ID_DOCUMENT"))
                .andExpect(jsonPath("$.steps[1].status").value("COMPLETED"));
    }

    @Test
    void 형식_오류와_빠진_항목은_모두_모아_400이고_값은_싣지_않으며_아무것도_저장하지_않는다() throws Exception {
        String body = save("""
                {"fields": [
                  {"fieldKey": "nationality", "value": "Philippines"},
                  {"fieldKey": "birthDate", "value": "2999-01-01"},
                  {"fieldKey": "visaType", "value": "Z-99"},
                  {"fieldKey": "nameEn", "value": "MARK ANTHONY REYES"},
                  {"fieldKey": "registrationNo", "value": "950815-5123456"},
                  {"fieldKey": "address", "value": "Seoul"},
                  {"fieldKey": "visaType", "value": "E-9"}
                ]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn().getResponse().getContentAsString();

        JsonNode errors = JsonMapper.builder().build().readTree(body).path("details").path("fields");
        assertThat(errors.valueStream().map(e -> e.path("field").stringValue() + ":" + e.path("reason").stringValue()))
                .containsExactly("fields[0].value:must be ISO 3166-1 alpha-3 (e.g. PHL)",
                        "fields[1].value:must not be in the future", "fields[2].value:unknown visa type",
                        "fields[3].fieldKey:not a visa profile field", "fields[4].fieldKey:not a visa profile field",
                        "fields[5].fieldKey:unknown fieldKey", "fields[6].fieldKey:duplicate fieldKey",
                        "stayExpiry:required");
        assertThat(body).doesNotContain("Philippines", "REYES", "950815-5123456", "Seoul");
        assertNothingSaved();
    }

    @Test
    void 날짜_형식이_틀리면_400() throws Exception {
        save("""
                {"fields": [
                  {"fieldKey": "nationality", "value": "PHL"},
                  {"fieldKey": "birthDate", "value": "1995-08-15"},
                  {"fieldKey": "visaType", "value": "E-9"},
                  {"fieldKey": "stayExpiry", "value": "03/01/2027"}
                ]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("fields[3].value"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("must be YYYY-MM-DD"));
        assertNothingSaved();
    }

    @Test
    void 체류_만료일이_지났으면_400() throws Exception {
        String yesterday = LocalDate.now(KstDates.KST).minusDays(1).toString();

        save(VALID.replace("2027-03-01", yesterday))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("fields[3].value"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("must not be in the past"));
        assertNothingSaved();
    }

    @Test
    void 빈_목록이나_빈_값은_400() throws Exception {
        save("{\"fields\": []}").andExpect(status().isBadRequest());
        save("{\"fields\": [{\"fieldKey\": \"stayExpiry\", \"value\": \" \"}]}").andExpect(status().isBadRequest());
        assertNothingSaved();
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
    }

    private void assertNothingSaved() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM visa_profiles WHERE user_id = ?", Integer.class,
                userId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM onboarding_progress WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    private java.util.Map<String, Object> profile() {
        return jdbcTemplate.queryForMap("""
                SELECT vt.code, vp.nationality_code, vp.birth_date, vp.stay_expires_on, vp.current_stay_started_on,
                  vp.prior_stay_months, vp.source_document_id, vp.evidence_status
                FROM visa_profiles vp JOIN visa_types vt ON vt.id = vp.visa_type_id WHERE vp.user_id = ?""", userId);
    }

    private ResultActions save(String json) throws Exception {
        return mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(json)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
