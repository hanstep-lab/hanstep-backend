package com.hanstep.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.support.TestUsers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 등록증 OCR Worker 내부 API. 실제 DB, 테스트마다 롤백. documents·ai_jobs 행은 테스트 안에서 새로 넣는다. */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class DocumentInternalApiTest {

    private static final String SOURCE_URL = "/internal/v1/ai-context/documents/{id}/source";
    private static final String OCR_RESULT_URL = "/internal/v1/ai/documents/{id}/ocr-result";
    private static final String MODEL = "ppocrv5-ko-mobile-int8";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = TestUsers.create(jdbcTemplate, "test-ocr-" + UUID.randomUUID(), "MARK ANTHONY REYES");
    }

    // ---------- 원본 위치 조회 ----------

    @Test
    void 원본_위치는_objectKey_side_sha256만_준다() throws Exception {
        String sha256 = randomHash();
        long id = insertDoc("FRONT", "OCR_QUEUED", "arc/front/" + sha256 + ".jpg", sha256);

        String body = mockMvc.perform(get(SOURCE_URL, id).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").value("arc/front/" + sha256 + ".jpg"))
                .andExpect(jsonPath("$.side").value("FRONT"))
                .andExpect(jsonPath("$.sha256").value(sha256))
                .andReturn().getResponse().getContentAsString();

        // 사용자·OCR 값 같은 개인정보는 없다
        assertThat(MAPPER.readTree(body).propertyNames()).containsExactlyInAnyOrder("objectKey", "side", "sha256");
    }

    @Test
    void 원본이_삭제됐으면_objectKey는_null() throws Exception {
        long id = insertDoc("FRONT", "OCR_QUEUED", null, randomHash());

        mockMvc.perform(get(SOURCE_URL, id).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.side").value("FRONT"));
    }

    @Test
    void 원본_조회에서_없는_문서면_404() throws Exception {
        mockMvc.perform(get(SOURCE_URL, 999999999L).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.details.documentId").value(999999999L));
    }

    @Test
    void 원본_조회도_토큰이_없으면_401() throws Exception {
        long id = insertDoc("FRONT", "OCR_QUEUED");

        mockMvc.perform(get(SOURCE_URL, id)).andExpect(status().isUnauthorized());
    }

    // ---------- OCR 결과 보고 ----------

    @Test
    void OCR_결과를_항목별로_저장하고_문서는_확인_대기로() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");

        ocrResult(id, json(body(id))).andExpect(status().isNoContent());

        Map<String, Object> doc = docRow(id);
        assertThat(doc.get("status")).isEqualTo("PENDING_CONFIRMATION");
        assertThat(doc.get("ocr_model_version")).isEqualTo(MODEL);
        assertThat(doc.get("ocr_latency_ms")).isEqualTo(842L);
        Map<String, Map<String, Object>> fields = fieldRows(id);
        assertThat(fields).containsOnlyKeys("nameEn", "nationality", "birthDate", "visaType", "stayExpiry",
                "registrationNo");
        assertField(fields.get("nameEn"), "MARK ANTHONY REYES", "0.9812", false, "EXTRACTED");
        assertField(fields.get("nationality"), "PHL", "0.9655", false, "EXTRACTED");
        assertField(fields.get("birthDate"), "1995-08-15", "0.9734", false, "EXTRACTED");
        assertField(fields.get("visaType"), "E-9", "0.9521", false, "EXTRACTED");
        assertField(fields.get("stayExpiry"), null, null, false, "MISSING");
        assertField(fields.get("registrationNo"), "950815-5******", "0.9907", true, "EXTRACTED");
        assertThat(fields.values()).allSatisfy(f -> {
            assertThat(f.get("confirmed_value")).isNull();
            assertThat(f.get("confirmed_at")).isNull();
        });
    }

    // #73 이 업로드 뒤 문서 상태를 OCR_QUEUED 등으로 바꾸기 전에도 후에도 같은 동작이어야 한다.
    // 발행 confirm 기록보다 Worker 결과가 먼저 오면 문서는 UPLOADED, 작업은 PENDING 일 수 있다
    @Test
    void 업로드_상태_그대로인_문서도_결과를_받는다() throws Exception {
        long id = insertDoc("FRONT", "UPLOADED");
        long job = insertJob("OCR", "DOCUMENT", id, "PENDING");

        ocrResult(id, json(body(id))).andExpect(status().isNoContent());

        Map<String, Object> doc = docRow(id);
        assertThat(doc.get("status")).isEqualTo("PENDING_CONFIRMATION");
        assertThat(doc.get("ocr_model_version")).isEqualTo(MODEL);
        assertThat(doc.get("ocr_latency_ms")).isEqualTo(842L);
        assertThat(fieldRows(id)).hasSize(6);
        assertThat(jobRow(job).get("status")).isEqualTo("COMPLETED");
        assertThat(jobRow(job).get("completed_at")).isNotNull();
    }

    @Test
    void OCR_결과를_받으면_그_문서의_OCR_작업을_COMPLETED로() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        long job = insertJob("OCR", "DOCUMENT", id, "PROCESSING");

        ocrResult(id, json(body(id))).andExpect(status().isNoContent());

        Map<String, Object> row = jobRow(job);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("error_code")).isNull();
    }

    @Test
    void 아직_시작하지_않은_OCR_작업도_COMPLETED로() throws Exception {
        long id = insertDoc("FRONT", "OCR_QUEUED");
        long job = insertJob("OCR", "DOCUMENT", id, "QUEUED");

        ocrResult(id, json(body(id))).andExpect(status().isNoContent());

        assertThat(jobRow(job).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void OCR_작업이_실패했으면_409이고_아무것도_바꾸지_않는다() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        long job = insertJob("OCR", "DOCUMENT", id, "FAILED");
        Object completedAt = jobRow(job).get("completed_at");

        ocrResult(id, json(body(id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ALREADY_FINISHED"))
                .andExpect(jsonPath("$.details.documentId").value(id))
                .andExpect(jsonPath("$.details.jobId").value(job))
                .andExpect(jsonPath("$.details.status").value("FAILED"));

        Map<String, Object> doc = docRow(id);
        assertThat(doc.get("status")).isEqualTo("OCR_PROCESSING");
        assertThat(doc.get("ocr_model_version")).isNull();
        assertThat(doc.get("ocr_latency_ms")).isNull();
        assertThat(fieldRows(id)).isEmpty();
        Map<String, Object> row = jobRow(job);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_code")).isEqualTo("OCR_FAILED");
        assertThat(row.get("completed_at")).isEqualTo(completedAt);
    }

    @Test
    void 다른_문서의_작업과_다른_종류의_작업은_건드리지_않는다() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        long other = insertDoc("FRONT", "OCR_PROCESSING");
        long otherJob = insertJob("OCR", "DOCUMENT", other, "PROCESSING");
        // 같은 resource_id 의 정책 적재 작업(리소스 종류가 다름)
        long policyJob = insertJob("POLICY_INGEST", "POLICY_DOCUMENT", id, "PROCESSING");

        ocrResult(id, json(body(id))).andExpect(status().isNoContent());

        assertThat(jobRow(otherJob).get("status")).isEqualTo("PROCESSING");
        assertThat(jobRow(policyJob).get("status")).isEqualTo("PROCESSING");
        assertThat(docRow(other).get("status")).isEqualTo("OCR_PROCESSING");
    }

    @Test
    void 같은_결과를_다시_보내면_204이고_아무것도_바꾸지_않는다() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        long job = insertJob("OCR", "DOCUMENT", id, "PROCESSING");
        String body = json(body(id));
        ocrResult(id, body).andExpect(status().isNoContent());
        Object completedAt = jobRow(job).get("completed_at");
        List<Long> fieldIds = fieldIds(id);

        ocrResult(id, body).andExpect(status().isNoContent());

        assertThat(fieldIds(id)).isEqualTo(fieldIds);
        assertThat(docRow(id).get("status")).isEqualTo("PENDING_CONFIRMATION");
        assertThat(jobRow(job).get("status")).isEqualTo("COMPLETED");
        assertThat(jobRow(job).get("completed_at")).isEqualTo(completedAt);
    }

    @Test
    void 사용자가_확인_화면에서_고친_뒤에_온_재전송도_204() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        String body = json(body(id));
        ocrResult(id, body).andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE document_fields SET confirmed_value = '2027-10-04', status = 'CORRECTED'"
                + " WHERE document_id = ? AND field_key = 'stayExpiry'", id);

        ocrResult(id, body).andExpect(status().isNoContent());

        Map<String, Object> stayExpiry = fieldRows(id).get("stayExpiry");
        assertThat(stayExpiry.get("confirmed_value")).isEqualTo("2027-10-04");
        assertThat(stayExpiry.get("status")).isEqualTo("CORRECTED");
    }

    @Test
    void 신뢰도는_소수_4자리로_반올림해_저장하고_재전송도_같은_결과로_본다() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        Map<String, Object> body = body(id);
        fields(body).set(0, field("nameEn", "MARK ANTHONY REYES", 0.98765, false));

        ocrResult(id, json(body)).andExpect(status().isNoContent());
        ocrResult(id, json(body)).andExpect(status().isNoContent());

        assertThat((BigDecimal) fieldRows(id).get("nameEn").get("confidence")).isEqualByComparingTo("0.9877");
    }

    @Test
    void 처리_시간만_다른_재전송은_같은_결과로_보고_204이고_처음_값을_유지한다() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        long job = insertJob("OCR", "DOCUMENT", id, "PROCESSING");
        ocrResult(id, json(body(id))).andExpect(status().isNoContent());
        Object completedAt = jobRow(job).get("completed_at");
        List<Long> fieldIds = fieldIds(id);
        Map<String, Object> slower = body(id);
        slower.put("latencyMs", 900);

        ocrResult(id, json(slower)).andExpect(status().isNoContent());

        assertThat(docRow(id).get("ocr_latency_ms")).isEqualTo(842L);
        assertThat(fieldIds(id)).isEqualTo(fieldIds);
        assertThat(jobRow(job).get("completed_at")).isEqualTo(completedAt);
    }

    @Test
    void 이미_다른_결과가_반영됐으면_409이고_그대로() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        ocrResult(id, json(body(id))).andExpect(status().isNoContent());
        Map<String, Object> otherValue = body(id);
        fields(otherValue).set(0, field("nameEn", "MARK ANTONY REYES", 0.9812, false));
        Map<String, Object> otherConfidence = body(id);
        fields(otherConfidence).set(0, field("nameEn", "MARK ANTHONY REYES", 0.8812, false));
        Map<String, Object> otherMasked = body(id);
        fields(otherMasked).set(0, field("nameEn", "MARK ANTHONY REYES", 0.9812, true));
        Map<String, Object> otherModel = body(id);
        otherModel.put("modelVersion", "ppocrv5-ko-server-fp32");

        for (Map<String, Object> body : List.of(otherValue, otherConfidence, otherMasked, otherModel)) {
            ocrResult(id, json(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                    .andExpect(jsonPath("$.details.documentId").value(id))
                    .andExpect(jsonPath("$.details.status").value("PENDING_CONFIRMATION"));
        }

        Map<String, Object> nameEn = fieldRows(id).get("nameEn");
        assertThat(nameEn.get("extracted_value")).isEqualTo("MARK ANTHONY REYES");
        assertThat((BigDecimal) nameEn.get("confidence")).isEqualByComparingTo("0.9812");
        assertThat(nameEn.get("is_masked")).isEqualTo(false);
        assertThat(docRow(id).get("ocr_model_version")).isEqualTo(MODEL);
    }

    @Test
    void 확정됐거나_실패한_문서에_온_결과는_409() throws Exception {
        for (String status : new String[] {"CONFIRMED", "FAILED"}) {
            long id = insertDoc("FRONT", status);

            ocrResult(id, json(body(id)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                    .andExpect(jsonPath("$.details.status").value(status));

            assertThat(fieldRows(id)).as(status).isEmpty();
        }
    }

    @Test
    void 등록번호가_마스킹되지_않았으면_400이고_아무것도_저장하지_않는다() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        long job = insertJob("OCR", "DOCUMENT", id, "PROCESSING");
        Map<String, Object> notMasked = body(id);
        fields(notMasked).set(5, field("registrationNo", "950815-5******", 0.9907, false));
        Map<String, Object> fullNumber = body(id);
        fields(fullNumber).set(5, field("registrationNo", "950815-5123456", 0.9907, true));

        ocrResult(id, json(notMasked))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("fields[5].isMasked"));
        String response = ocrResult(id, json(fullNumber))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("fields[5].value"))
                .andReturn().getResponse().getContentAsString();

        // 오류 응답에 받은 번호를 싣지 않는다
        assertThat(response).doesNotContain("5123456");
        assertThat(fieldRows(id)).isEmpty();
        assertThat(docRow(id).get("status")).isEqualTo("OCR_PROCESSING");
        assertThat(jobRow(job).get("status")).isEqualTo("PROCESSING");
    }

    @Test
    void 등록번호_마스킹_형식이_아니면_400() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        for (String value : new String[] {"9508155******", "950815-5*****", "950815-*******", "950815-5******1",
                "95O815-5******", "950815-5XXXXXX", " 950815-5******", ""}) {
            Map<String, Object> body = body(id);
            fields(body).set(5, field("registrationNo", value, 0.9907, true));

            ocrResult(id, json(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.fields[0].field").value("fields[5].value"));
        }
        assertThat(fieldRows(id)).isEmpty();
    }

    @Test
    void 등록번호를_못_읽었으면_MISSING으로_저장() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        Map<String, Object> body = body(id);
        fields(body).set(5, field("registrationNo", null, null, true));

        ocrResult(id, json(body)).andExpect(status().isNoContent());

        assertField(fieldRows(id).get("registrationNo"), null, null, true, "MISSING");
    }

    @Test
    void 모르는_fieldKey는_400() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        Map<String, Object> body = body(id);
        fields(body).add(field("address", "GYEONGSANGNAM-DO", 0.9, false));

        ocrResult(id, json(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("fields[6].fieldKey"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("unknown fieldKey"));
        assertThat(fieldRows(id)).isEmpty();
    }

    @Test
    void 중복되거나_빠진_fieldKey는_400() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        Map<String, Object> duplicated = body(id);
        fields(duplicated).add(field("nameEn", "MARK ANTHONY REYES", 0.9812, false));
        Map<String, Object> missing = body(id);
        fields(missing).remove(4); // stayExpiry

        ocrResult(id, json(duplicated))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("fields[6].fieldKey"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("duplicate fieldKey"));
        ocrResult(id, json(missing))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("fields"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("missing fieldKey stayExpiry"));
        assertThat(fieldRows(id)).isEmpty();
    }

    @Test
    void documentId가_경로와_다르거나_side가_FRONT가_아니면_400() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        Map<String, Object> otherId = body(id);
        otherId.put("documentId", id + 1);
        Map<String, Object> back = body(id);
        back.put("side", "BACK");

        ocrResult(id, json(otherId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("documentId"));
        ocrResult(id, json(back))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("side"));
        assertThat(docRow(id).get("status")).isEqualTo("OCR_PROCESSING");
    }

    @Test
    void 문서의_side가_FRONT가_아니면_409() throws Exception {
        long id = insertDoc("SINGLE", "OCR_PROCESSING");

        ocrResult(id, json(body(id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.details.field").value("side"))
                .andExpect(jsonPath("$.details.expected").value("SINGLE"));
        assertThat(fieldRows(id)).isEmpty();
    }

    @Test
    void 본문_형식이_틀리면_400() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");
        Map<String, Object> tooConfident = body(id);
        fields(tooConfident).set(0, field("nameEn", "MARK ANTHONY REYES", 1.5, false));
        Map<String, Object> negativeConfidence = body(id);
        fields(negativeConfidence).set(0, field("nameEn", "MARK ANTHONY REYES", -0.1, false));
        Map<String, Object> noMasked = body(id);
        fields(noMasked).get(0).remove("isMasked");
        Map<String, Object> negativeLatency = body(id);
        negativeLatency.put("latencyMs", -1);
        Map<String, Object> blankModel = body(id);
        blankModel.put("modelVersion", " ");
        Map<String, Object> longValue = body(id);
        fields(longValue).set(0, field("nameEn", "A".repeat(256), 0.9, false));
        Map<String, Object> noFields = body(id);
        noFields.remove("fields");
        Map<String, Object> nullField = body(id);
        fields(nullField).set(0, null);

        for (Map<String, Object> body : List.of(tooConfident, negativeConfidence, noMasked, negativeLatency,
                blankModel, longValue, noFields, nullField)) {
            ocrResult(id, json(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        ocrResult(id, "{\"documentId\":" + id + ",\"side\":\"LEFT\"}").andExpect(status().isBadRequest());
        assertThat(fieldRows(id)).isEmpty();
    }

    @Test
    void OCR_결과_보고에서_없는_문서면_404() throws Exception {
        ocrResult(999999999L, json(body(999999999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.details.documentId").value(999999999L));
    }

    @Test
    void OCR_결과_보고도_토큰이_없으면_401() throws Exception {
        long id = insertDoc("FRONT", "OCR_PROCESSING");

        mockMvc.perform(post(OCR_RESULT_URL, id).contentType(MediaType.APPLICATION_JSON).content(json(body(id))))
                .andExpect(status().isUnauthorized());
        assertThat(docRow(id).get("status")).isEqualTo("OCR_PROCESSING");
    }

    @Test
    void Swagger에_Reyes_합성_요청_예시가_있다() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode paths = MAPPER.readTree(docs).path("paths");

        assertThat(paths.has("/internal/v1/ai-context/documents/{documentId}/source")).isTrue();
        JsonNode content = paths.path("/internal/v1/ai/documents/{documentId}/ocr-result").path("post")
                .path("requestBody").path("content").path(MediaType.APPLICATION_JSON_VALUE);
        assertThat(content.path("schema").path("$ref").stringValue()).endsWith("/OcrResultRequest");
        assertThat(content.path("examples").toString())
                .contains("MARK ANTHONY REYES", "PHL", "1995-08-15", "950815-5******");
    }

    // ---------- helpers ----------

    private ResultActions ocrResult(long id, String body) throws Exception {
        return mockMvc.perform(post(OCR_RESULT_URL, id)
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // 합성 데이터(가상 인물 Reyes). 실제 등록증 값이 아니다
    private static Map<String, Object> body(long documentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", documentId);
        body.put("modelVersion", MODEL);
        body.put("latencyMs", 842);
        body.put("side", "FRONT");
        body.put("fields", new ArrayList<>(List.of(
                field("nameEn", "MARK ANTHONY REYES", 0.9812, false),
                field("nationality", "PHL", 0.9655, false),
                field("birthDate", "1995-08-15", 0.9734, false),
                field("visaType", "E-9", 0.9521, false),
                field("stayExpiry", null, null, false),
                field("registrationNo", "950815-5******", 0.9907, true))));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("fields");
    }

    private static Map<String, Object> field(String key, String value, Double confidence, boolean masked) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("fieldKey", key);
        field.put("value", value);
        field.put("confidence", confidence);
        field.put("isMasked", masked);
        return field;
    }

    private static String json(Map<String, Object> body) {
        return MAPPER.writeValueAsString(body);
    }

    private static void assertField(Map<String, Object> row, String value, String confidence, boolean masked,
                                    String status) {
        assertThat(row.get("extracted_value")).isEqualTo(value);
        if (confidence == null) {
            assertThat(row.get("confidence")).isNull();
        } else {
            assertThat((BigDecimal) row.get("confidence")).isEqualByComparingTo(confidence);
        }
        assertThat(row.get("is_masked")).isEqualTo(masked);
        assertThat(row.get("status")).isEqualTo(status);
    }

    private long insertDoc(String side, String status) {
        return insertDoc(side, status, "arc/front/" + UUID.randomUUID() + ".jpg", randomHash());
    }

    // #73 업로드처럼 등록증 1장 = 문서 1건. CONFIRMED 는 confirmed_at 이 있어야 한다(ck_documents_confirmed)
    private long insertDoc(String side, String status, String objectKey, String sha256) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO documents (user_id, document_type, side, object_key, content_type, file_size_bytes,
                      sha256, status, retention_until, confirmed_at)
                    VALUES (?, 'ARC', ?, ?, 'image/jpeg', 245760, ?, ?, CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY, %s)
                    """.formatted(status.equals("CONFIRMED") ? "CURRENT_TIMESTAMP(3)" : "NULL"),
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, side);
            ps.setString(3, objectKey);
            ps.setString(4, sha256);
            ps.setString(5, status);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    // 끝난 상태는 completed_at 이 있어야 한다(ck_ai_jobs_done)
    private long insertJob(String jobType, String resourceType, long resourceId, String status) {
        boolean done = status.equals("COMPLETED") || status.equals("FAILED");
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO ai_jobs (user_id, job_type, resource_type, resource_id, idempotency_key, correlation_id,
                      status, error_code, completed_at)
                    VALUES (?, ?, ?, ?, ?, 'req_test', ?, %s, %s)
                    """.formatted(status.equals("FAILED") ? "'OCR_FAILED'" : "NULL",
                    done ? "CURRENT_TIMESTAMP(3)" : "NULL"), Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, jobType);
            ps.setString(3, resourceType);
            ps.setLong(4, resourceId);
            ps.setString(5, "test-" + UUID.randomUUID());
            ps.setString(6, status);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    // 테스트 트랜잭션 안이라 JPA 변경을 먼저 내려보내야 JDBC 로 보인다
    private Map<String, Object> docRow(long id) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("SELECT * FROM documents WHERE id = ?", id);
    }

    private Map<String, Map<String, Object>> fieldRows(long documentId) {
        entityManager.flush();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT * FROM document_fields WHERE document_id = ?", documentId)
                .forEach(row -> rows.put((String) row.get("field_key"), row));
        return rows;
    }

    private List<Long> fieldIds(long documentId) {
        entityManager.flush();
        return jdbcTemplate.queryForList("SELECT id FROM document_fields WHERE document_id = ? ORDER BY id",
                Long.class, documentId);
    }

    private Map<String, Object> jobRow(long id) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("SELECT * FROM ai_jobs WHERE id = ?", id);
    }

    private static String randomHash() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
