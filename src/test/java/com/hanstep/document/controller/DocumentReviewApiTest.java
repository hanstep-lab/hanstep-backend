package com.hanstep.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.aijob.dto.JobFailedRequest;
import com.hanstep.aijob.service.AiJobStatusService;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.document.ArcImages;
import com.hanstep.document.FakeArcStorageConfig;
import com.hanstep.document.FakeArcStorageConfig.FakeArcImageStorage;
import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.dto.OcrFieldResult;
import com.hanstep.document.dto.OcrResultRequest;
import com.hanstep.document.service.DocumentInternalService;
import com.hanstep.support.TestUsers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 등록증 OCR 결과 확인 흐름(DOC-02 조회·DOC-03 수정·DOC-04 확정). 업로드(DOC-01) → worker 결과 보고
 * (DocumentInternalService, 실제 경로) → 조회·수정·확정.
 * 실제 DB, 테스트마다 롤백. R2 대신 메모리 저장소. OCR 값은 가상 인물(Reyes) 합성 데이터.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FakeArcStorageConfig.class)
class DocumentReviewApiTest {

    private static final String URL = DocumentApiTest.URL + "/{id}";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Autowired
    FakeArcImageStorage storage;

    @Autowired
    DocumentInternalService documentInternalService;

    @Autowired
    AiJobStatusService aiJobStatusService;

    long userId;
    String bearer;

    @BeforeEach
    void setUp() {
        storage.reset();
        userId = TestUsers.create(jdbcTemplate, "test-doc-review-" + UUID.randomUUID(), "MARK ANTHONY REYES");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    // ---------- DOC-02 조회 ----------

    @Test
    void OCR_결과_전에는_UPLOADED이고_fields가_비어_있다() throws Exception {
        long id = upload();

        mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(id))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.ocrJob.status").value("PENDING"))
                .andExpect(jsonPath("$.fields").isEmpty());
    }

    @Test
    void OCR_결과가_오면_6개_항목을_화면_순서로_주고_신뢰도_낮거나_없는_항목은_확인_필요() throws Exception {
        long id = upload();
        recordOcr(id, new BigDecimal("0.7999"));

        JsonNode body = json(mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.ocrJob.status").value("COMPLETED")));

        JsonNode fields = body.path("fields");
        assertThat(fields.valueStream().map(f -> f.path("fieldKey").stringValue()))
                .containsExactly("nameEn", "nationality", "birthDate", "visaType", "stayExpiry", "registrationNo");
        assertThat(field(fields, "nameEn").path("value").stringValue()).isEqualTo("MARK ANTHONY REYES");
        assertThat(field(fields, "nameEn").path("needsReview").booleanValue()).isFalse();
        assertThat(field(fields, "nameEn").path("status").stringValue()).isEqualTo("EXTRACTED");
        // 0.8 미만
        assertThat(field(fields, "visaType").path("confidence").decimalValue()).isEqualByComparingTo("0.7999");
        assertThat(field(fields, "visaType").path("needsReview").booleanValue()).isTrue();
        // 앞면에 없어 못 읽음
        assertThat(field(fields, "stayExpiry").path("value").isNull()).isTrue();
        assertThat(field(fields, "stayExpiry").path("status").stringValue()).isEqualTo("MISSING");
        assertThat(field(fields, "stayExpiry").path("needsReview").booleanValue()).isTrue();
        // 등록번호는 마스킹 값만
        assertThat(field(fields, "registrationNo").path("value").stringValue()).isEqualTo("950815-5******");
        assertThat(field(fields, "registrationNo").path("isMasked").booleanValue()).isTrue();
    }

    @Test
    void OCR_작업이_실패하면_status_FAILED와_failureCode() throws Exception {
        long id = upload();
        long jobId = json(mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer)))
                .path("ocrJob").path("jobId").longValue();
        // worker 의 job_failed(INT-10) 와 같은 상태 전이
        aiJobStatusService.markFailed(jobId, new JobFailedRequest("OCR_FAILED", "image too blurry", false));

        mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("OCR_FAILED"))
                .andExpect(jsonPath("$.ocrJob.status").value("FAILED"))
                .andExpect(jsonPath("$.ocrJob.retryable").value(false));
        // documents.status 에는 FAILED 를 쓰지 않는다(결정 2)
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, id))
                .isEqualTo("UPLOADED");
    }

    @Test
    void 실패하지_않았으면_failureCode는_없다() throws Exception {
        long id = upload();

        mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.failureCode").doesNotExist());
    }

    @Test
    void 다른_사용자의_문서나_없는_문서는_404() throws Exception {
        long id = upload();
        long otherId = TestUsers.create(jdbcTemplate, "test-doc-review-" + UUID.randomUUID(), "OTHER USER");

        mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(otherId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get(URL, Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
    }

    // ---------- daysRemaining(#131) ----------

    @Test
    void 체류_만료일_값이_없으면_daysRemaining은_null() throws Exception {
        long id = upload();
        mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.daysRemaining").value(org.hamcrest.Matchers.nullValue()));

        recordOcr(id, new BigDecimal("0.95"));
        mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.daysRemaining").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void OCR_값의_형식이_틀리면_daysRemaining은_null() throws Exception {
        long id = upload();
        recordOcr(id, new BigDecimal("0.95"), "01/03/2027");

        mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysRemaining").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void daysRemaining은_stayExpiry_지금_값으로_오늘_KST부터_계산하고_확정_후에는_null() throws Exception {
        long id = upload();
        recordOcr(id, new BigDecimal("0.95"), "2027-01-01");
        LocalDate today = LocalDate.now(KstDates.KST);

        mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.daysRemaining")
                        .value(ChronoUnit.DAYS.between(today, LocalDate.parse("2027-01-01"))));
        // 사용자가 고친 값 기준
        patchFields(id, "{\"fields\": [{\"fieldKey\": \"stayExpiry\", \"value\": \"2027-03-01\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysRemaining")
                        .value(ChronoUnit.DAYS.between(today, LocalDate.parse("2027-03-01"))));

        confirm(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.daysRemaining").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---------- DOC-03 수정 ----------

    @Test
    void 보낸_항목만_바꾸고_OCR값과_같으면_CONFIRMED_다르면_CORRECTED() throws Exception {
        long id = upload();
        recordOcr(id, new BigDecimal("0.7"));

        JsonNode fields = json(patchFields(id, """
                {"fields": [
                  {"fieldKey": "stayExpiry", "value": "2027-03-01"},
                  {"fieldKey": "visaType", "value": "E-9"},
                  {"fieldKey": "nameEn", "value": " MARK ANTHONY REYES JR "}
                ]}""").andExpect(status().isOk())).path("fields");

        assertThat(field(fields, "stayExpiry").path("value").stringValue()).isEqualTo("2027-03-01");
        assertThat(field(fields, "stayExpiry").path("status").stringValue()).isEqualTo("CORRECTED");
        assertThat(field(fields, "stayExpiry").path("needsReview").booleanValue()).isFalse();
        assertThat(field(fields, "visaType").path("status").stringValue()).isEqualTo("CONFIRMED");
        assertThat(field(fields, "visaType").path("needsReview").booleanValue()).isFalse();
        assertThat(field(fields, "nameEn").path("value").stringValue()).isEqualTo("MARK ANTHONY REYES JR");
        assertThat(field(fields, "nameEn").path("status").stringValue()).isEqualTo("CORRECTED");
        // 보내지 않은 항목은 그대로
        assertThat(field(fields, "nationality").path("status").stringValue()).isEqualTo("EXTRACTED");
        assertThat(jdbcTemplate.queryForObject("SELECT extracted_value FROM document_fields WHERE document_id = ? "
                + "AND field_key = 'nameEn'", String.class, id)).isEqualTo("MARK ANTHONY REYES");
    }

    @Test
    void 형식이_틀린_항목은_모두_모아_400이고_값은_싣지_않으며_아무것도_바꾸지_않는다() throws Exception {
        long id = upload();
        recordOcr(id, new BigDecimal("0.95"));

        String body = patchFields(id, """
                {"fields": [
                  {"fieldKey": "nationality", "value": "Philippines"},
                  {"fieldKey": "birthDate", "value": "2999-01-01"},
                  {"fieldKey": "stayExpiry", "value": "03/01/2027"},
                  {"fieldKey": "visaType", "value": "Z-99"},
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
                        "fields[1].value:must not be in the future", "fields[2].value:must be YYYY-MM-DD",
                        "fields[3].value:unknown visa type", "fields[4].fieldKey:registrationNo cannot be edited",
                        "fields[5].fieldKey:unknown fieldKey", "fields[6].fieldKey:duplicate fieldKey");
        assertThat(body).doesNotContain("Philippines", "950815-5123456", "Seoul");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_fields WHERE document_id = ? "
                + "AND status IN ('CONFIRMED', 'CORRECTED')", Integer.class, id)).isZero();
    }

    @Test
    void 지난_체류_만료일로_고치면_400이고_바꾸지_않는다() throws Exception {
        long id = upload();
        recordOcr(id, new BigDecimal("0.95"));
        String yesterday = LocalDate.now(KstDates.KST).minusDays(1).toString();

        patchFields(id, "{\"fields\": [{\"fieldKey\": \"stayExpiry\", \"value\": \"" + yesterday + "\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("fields[0].value"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("must not be in the past"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_fields WHERE document_id = ? "
                + "AND status IN ('CONFIRMED', 'CORRECTED')", Integer.class, id)).isZero();
    }

    @Test
    void OCR_결과_전이면_수정은_409() throws Exception {
        long id = upload();

        patchFields(id, "{\"fields\": [{\"fieldKey\": \"stayExpiry\", \"value\": \"2027-03-01\"}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 빈_목록이나_빈_값은_400() throws Exception {
        long id = upload();
        recordOcr(id, new BigDecimal("0.95"));

        patchFields(id, "{\"fields\": []}").andExpect(status().isBadRequest());
        patchFields(id, "{\"fields\": [{\"fieldKey\": \"stayExpiry\", \"value\": \" \"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("fields[0].value"));
    }

    // ---------- DOC-04 확정 ----------

    @Test
    void 확정하면_visa_profiles에_반영하고_원본과_항목_값을_지운다() throws Exception {
        long id = upload();
        String key = jdbcTemplate.queryForObject("SELECT object_key FROM documents WHERE id = ?", String.class, id);
        recordOcr(id, new BigDecimal("0.95"));
        patchFields(id, "{\"fields\": [{\"fieldKey\": \"stayExpiry\", \"value\": \"2027-03-01\"}]}")
                .andExpect(status().isOk());
        assertThat(storage.objects).containsKey(key);

        JsonNode body = json(confirm(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").exists()));

        // 응답: 값은 null, status·confidence 만
        JsonNode fields = body.path("fields");
        assertThat(fields.valueStream().allMatch(f -> f.path("value").isNull())).isTrue();
        assertThat(fields.valueStream().noneMatch(f -> f.path("needsReview").booleanValue())).isTrue();
        assertThat(field(fields, "nameEn").path("status").stringValue()).isEqualTo("CONFIRMED");
        assertThat(field(fields, "stayExpiry").path("status").stringValue()).isEqualTo("CORRECTED");
        assertThat(field(fields, "registrationNo").path("confidence").decimalValue()).isEqualByComparingTo("0.9907");
        assertThat(body.toString()).doesNotContain("950815", "REYES", "PHL");

        // visa_profiles: 국적·생년월일·체류자격·만료일, 근거 문서. 등록번호 칸은 없다
        var profile = jdbcTemplate.queryForMap("""
                SELECT vt.code, vp.nationality_code, vp.birth_date, vp.stay_expires_on, vp.source_document_id
                FROM visa_profiles vp JOIN visa_types vt ON vt.id = vp.visa_type_id WHERE vp.user_id = ?""", userId);
        assertThat(profile.get("code")).isEqualTo("E-9");
        assertThat(profile.get("nationality_code")).isEqualTo("PHL");
        assertThat(profile.get("birth_date").toString()).isEqualTo("1995-08-15");
        assertThat(profile.get("stay_expires_on").toString()).isEqualTo("2027-03-01");
        assertThat(((Number) profile.get("source_document_id")).longValue()).isEqualTo(id);

        // 원본 삭제, object_key NULL, 항목 값 삭제
        assertThat(storage.objects).doesNotContainKey(key);
        var doc = jdbcTemplate.queryForMap("SELECT object_key, original_deleted_at FROM documents WHERE id = ?", id);
        assertThat(doc.get("object_key")).isNull();
        assertThat(doc.get("original_deleted_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_fields WHERE document_id = ? "
                + "AND (extracted_value IS NOT NULL OR confirmed_value IS NOT NULL)", Integer.class, id)).isZero();

        // 다시 확정해도 그대로 200, 다시 지우지 않는다
        confirm(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(storage.deletes.get()).isEqualTo(1);
        // 확정 뒤에는 고칠 수 없다
        patchFields(id, "{\"fields\": [{\"fieldKey\": \"stayExpiry\", \"value\": \"2028-03-01\"}]}")
                .andExpect(status().isConflict());
    }

    @Test
    void 이미_있는_체류_프로필은_OCR_항목만_바꾸고_입국일은_그대로_둔다() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, nationality_code, birth_date,
                  current_stay_started_on, prior_stay_months)
                VALUES (?, (SELECT id FROM visa_types WHERE code = 'H-2'), '2026-12-31', 'VNM', '1990-01-01',
                  '2023-02-01', 12)""", userId);
        long id = upload();
        recordOcr(id, new BigDecimal("0.95"));
        patchFields(id, "{\"fields\": [{\"fieldKey\": \"stayExpiry\", \"value\": \"2027-03-01\"}]}");

        confirm(id).andExpect(status().isOk());

        var profile = jdbcTemplate.queryForMap("""
                SELECT vt.code, vp.nationality_code, vp.current_stay_started_on, vp.prior_stay_months
                FROM visa_profiles vp JOIN visa_types vt ON vt.id = vp.visa_type_id WHERE vp.user_id = ?""", userId);
        assertThat(profile.get("code")).isEqualTo("E-9");
        assertThat(profile.get("nationality_code")).isEqualTo("PHL");
        assertThat(profile.get("current_stay_started_on").toString()).isEqualTo("2023-02-01");
        assertThat(((Number) profile.get("prior_stay_months")).intValue()).isEqualTo(12);
    }

    @Test
    void 체류_만료일이_없으면_422이고_아무것도_바꾸지_않는다() throws Exception {
        long id = upload();
        recordOcr(id, new BigDecimal("0.95"));

        confirm(id).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.details.fields[0].field").value("stayExpiry"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("required"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, id))
                .isEqualTo("PENDING_CONFIRMATION");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM visa_profiles WHERE user_id = ?", Integer.class,
                userId)).isZero();
        assertThat(storage.deletes.get()).isZero();
    }

    @Test
    void 지난_체류_만료일이_남아_있으면_확정은_422이고_아무것도_바꾸지_않는다() throws Exception {
        long id = upload();
        recordOcr(id, new BigDecimal("0.95"), LocalDate.now(KstDates.KST).minusDays(1).toString());

        confirm(id).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.details.fields[0].field").value("stayExpiry"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("must not be in the past"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, id))
                .isEqualTo("PENDING_CONFIRMATION");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM visa_profiles WHERE user_id = ?", Integer.class,
                userId)).isZero();
        assertThat(storage.deletes.get()).isZero();
    }

    @Test
    void OCR_결과_전이면_확정은_409이고_남의_문서는_404() throws Exception {
        long id = upload();

        confirm(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        long otherId = TestUsers.create(jdbcTemplate, "test-doc-review-" + UUID.randomUUID(), "OTHER USER");
        mockMvc.perform(post(URL + "/confirm", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(otherId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch(URL + "/fields", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\": [{\"fieldKey\": \"stayExpiry\", \"value\": \"2027-03-01\"}]}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(otherId)))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    ResultActions patchFields(long id, String json) throws Exception {
        return mockMvc.perform(patch(URL + "/fields", id).contentType(MediaType.APPLICATION_JSON).content(json)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    ResultActions confirm(long id) throws Exception {
        return mockMvc.perform(post(URL + "/confirm", id).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    long upload() throws Exception {
        return json(mockMvc.perform(multipart(DocumentApiTest.URL)
                        .file(new MockMultipartFile("front", "front.jpg", "image/jpeg", ArcImages.jpeg(8 * 1024)))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isAccepted())).path("documentId").longValue();
    }

    /** worker 의 OCR 결과 보고(합성 데이터). visaType 신뢰도만 바꿔 넣는다. */
    void recordOcr(long documentId, BigDecimal visaTypeConfidence) {
        recordOcr(documentId, visaTypeConfidence, null);
    }

    /** stayExpiry 를 OCR 이 읽은 경우(앞면에는 보통 없음). */
    void recordOcr(long documentId, BigDecimal visaTypeConfidence, String stayExpiry) {
        documentInternalService.recordOcrResult(documentId, new OcrResultRequest(documentId, "ppocrv5-ko-mobile-int8",
                842, DocumentSide.FRONT, List.of(
                new OcrFieldResult("nameEn", "MARK ANTHONY REYES", new BigDecimal("0.9812"), false),
                new OcrFieldResult("nationality", "PHL", new BigDecimal("0.9655"), false),
                new OcrFieldResult("birthDate", "1995-08-15", new BigDecimal("0.9734"), false),
                new OcrFieldResult("visaType", "E-9", visaTypeConfidence, false),
                new OcrFieldResult("stayExpiry", stayExpiry, stayExpiry == null ? null : new BigDecimal("0.9"),
                        false),
                new OcrFieldResult("registrationNo", "950815-5******", new BigDecimal("0.9907"), true))));
    }

    static JsonNode field(JsonNode fields, String key) {
        return fields.valueStream().filter(f -> f.path("fieldKey").stringValue().equals(key)).findFirst().orElseThrow();
    }

    static JsonNode json(ResultActions result) throws Exception {
        return JsonMapper.builder().build().readTree(result.andReturn().getResponse().getContentAsString());
    }
}
