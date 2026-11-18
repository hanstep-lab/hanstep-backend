package com.hanstep.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.document.ArcImages;
import com.hanstep.document.FakeArcStorageConfig;
import com.hanstep.document.FakeArcStorageConfig.FakeArcImageStorage;
import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.dto.OcrFieldResult;
import com.hanstep.document.dto.OcrResultRequest;
import com.hanstep.document.service.DocumentInternalService;
import com.hanstep.support.TestUsers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 데모 계정 초기화(POST /api/v1/demo/reset, #131). 켜진 상태(hanstep.demo.reset-enabled=true).
 * 실제 DB, 테스트마다 롤백. R2 대신 메모리 저장소. 데모 시드 사용자 대신 demo- 로 시작하는 테스트 사용자를 만든다.
 */
@SpringBootTest(properties = "hanstep.demo.reset-enabled=true")
@AutoConfigureMockMvc
@Transactional
@Import(FakeArcStorageConfig.class)
class DemoResetApiTest {

    static final String URL = "/api/v1/demo/reset";

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
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        storage.reset();
    }

    @Test
    void 데모_계정의_온보딩과_등록증_문서를_지우고_visa_profiles는_남긴다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "MARK ANTHONY REYES");
        String bearer = bearer(userId);
        long confirmedId = insertConfirmedDocument(userId);
        insertVisaProfile(userId, confirmedId);
        finishSteps(userId, "LANGUAGE", "ID_DOCUMENT");
        // 확정 전 문서(R2 원본 있음, OCR 항목 6개)
        long pendingId = upload(bearer);
        recordOcr(pendingId);
        String key = jdbcTemplate.queryForObject("SELECT object_key FROM documents WHERE id = ?", String.class,
                pendingId);
        assertThat(storage.objects).containsKey(key);
        assertThat(count(OCR_JOBS, userId)).isEqualTo(1);
        // OCR 이 아닌 작업은 남긴다
        insertAiJob(userId, "DOCUMENT_ANALYSIS");
        // 테스트 트랜잭션 하나로 묶여 있어 요청 경계처럼 영속성 컨텍스트를 비운다(초기화는 JDBC 로 지운다)
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedDocuments").value(2))
                .andExpect(jsonPath("$.deletedDocumentFields").value(6))
                .andExpect(jsonPath("$.deletedOcrJobs").value(1))
                .andExpect(jsonPath("$.deletedOnboardingSteps").value(2))
                .andExpect(jsonPath("$.deletedOriginals").value(1))
                .andExpect(jsonPath("$.failedOriginals").value(0));

        assertThat(count("SELECT COUNT(*) FROM documents WHERE user_id = ?", userId)).isZero();
        assertThat(count(OCR_JOBS, userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM ai_jobs WHERE user_id = ? AND job_type = 'DOCUMENT_ANALYSIS'",
                userId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM document_fields WHERE document_id = ?", pendingId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM onboarding_progress WHERE user_id = ?", userId)).isZero();
        assertThat(storage.objects).doesNotContainKey(key);

        // visa_profiles 는 그대로(근거 문서만 SET NULL)
        Map<String, Object> profile = jdbcTemplate.queryForMap("""
                SELECT vt.code, vp.nationality_code, vp.stay_expires_on, vp.current_stay_started_on,
                  vp.prior_stay_months, vp.source_document_id
                FROM visa_profiles vp JOIN visa_types vt ON vt.id = vp.visa_type_id WHERE vp.user_id = ?""", userId);
        assertThat(profile.get("code")).isEqualTo("E-9");
        assertThat(profile.get("nationality_code")).isEqualTo("PHL");
        assertThat(profile.get("stay_expires_on").toString()).isEqualTo("2027-03-01");
        assertThat(profile.get("current_stay_started_on").toString()).isEqualTo("2023-02-01");
        assertThat(((Number) profile.get("prior_stay_months")).intValue()).isEqualTo(12);
        assertThat(profile.get("source_document_id")).isNull();

        // ONB-01 은 처음 상태
        mockMvc.perform(get("/api/v1/onboarding").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.currentStep").value("LANGUAGE"))
                .andExpect(jsonPath("$.steps[0].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.steps[1].status").value("NOT_STARTED"));

        // 같은 사진을 다시 올리면 새 문서·새 OCR 작업
        long again = upload(bearer);
        assertThat(again).isNotEqualTo(pendingId);
    }

    @Test
    void 지울_것이_없어도_200이고_0건() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "DEMO USER");

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedDocuments").value(0))
                .andExpect(jsonPath("$.deletedDocumentFields").value(0))
                .andExpect(jsonPath("$.deletedOcrJobs").value(0))
                .andExpect(jsonPath("$.deletedOnboardingSteps").value(0))
                .andExpect(jsonPath("$.deletedOriginals").value(0))
                .andExpect(jsonPath("$.failedOriginals").value(0));
    }

    @Test
    void R2_원본_삭제가_실패해도_200이고_DB는_지우며_실패_수를_준다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "MARK ANTHONY REYES");
        String bearer = bearer(userId);
        finishSteps(userId, "LANGUAGE");
        long pendingId = upload(bearer);
        String key = jdbcTemplate.queryForObject("SELECT object_key FROM documents WHERE id = ?", String.class,
                pendingId);
        entityManager.flush();
        entityManager.clear();
        storage.failDelete = true;

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedDocuments").value(1))
                .andExpect(jsonPath("$.deletedOcrJobs").value(1))
                .andExpect(jsonPath("$.deletedOnboardingSteps").value(1))
                .andExpect(jsonPath("$.deletedOriginals").value(0))
                .andExpect(jsonPath("$.failedOriginals").value(1));

        assertThat(storage.deletes.get()).isEqualTo(1);
        // 원본은 남았지만(가리키는 행 없음) DB 는 지워졌다
        assertThat(storage.objects).containsKey(key);
        assertThat(count("SELECT COUNT(*) FROM documents WHERE user_id = ?", userId)).isZero();
        assertThat(count(OCR_JOBS, userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM onboarding_progress WHERE user_id = ?", userId)).isZero();
    }

    @Test
    void 데모_계정이_아니면_403이고_아무것도_지우지_않는다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "test-demo-reset-" + UUID.randomUUID(), "OTHER USER");
        long documentId = insertConfirmedDocument(userId);
        finishSteps(userId, "LANGUAGE");

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(count("SELECT COUNT(*) FROM documents WHERE id = ?", documentId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM onboarding_progress WHERE user_id = ?", userId)).isEqualTo(1);
    }

    @Test
    void 다른_사용자의_데이터는_지우지_않는다() throws Exception {
        long demoId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "DEMO USER");
        long otherId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "OTHER DEMO USER");
        long otherDocument = insertConfirmedDocument(otherId);
        finishSteps(otherId, "LANGUAGE");
        insertAiJob(otherId, "OCR");
        insertAiJob(demoId, "OCR");

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer(demoId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedOcrJobs").value(1));
        assertThat(count(OCR_JOBS, otherId)).isEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM documents WHERE id = ?", otherDocument)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM onboarding_progress WHERE user_id = ?", otherId)).isEqualTo(1);
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(post(URL)).andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private static final String OCR_JOBS =
            "SELECT COUNT(*) FROM ai_jobs WHERE user_id = ? AND job_type = 'OCR' AND resource_type = 'DOCUMENT'";

    private void insertAiJob(long userId, String jobType) {
        jdbcTemplate.update("INSERT INTO ai_jobs (user_id, job_type, resource_type, resource_id, idempotency_key, "
                + "correlation_id) VALUES (?, ?, 'DOCUMENT', 1, ?, 'test')", userId, jobType,
                "test-" + UUID.randomUUID());
    }

    private String bearer(long userId) {
        return "Bearer " + jwtTokenProvider.issue(userId);
    }

    private int count(String sql, long id) {
        return jdbcTemplate.queryForObject(sql, Integer.class, id);
    }

    private long insertConfirmedDocument(long userId) {
        String sha = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO documents (user_id, document_type, side, content_type, file_size_bytes, sha256, status,
                  retention_until, confirmed_at)
                VALUES (?, 'ARC', 'FRONT', 'image/jpeg', 1, ?, 'CONFIRMED', NOW(3), NOW(3))""", userId, sha + sha);
        return jdbcTemplate.queryForObject("SELECT id FROM documents WHERE user_id = ? AND sha256 = ?", Long.class,
                userId, sha + sha);
    }

    private void insertVisaProfile(long userId, long documentId) {
        jdbcTemplate.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, nationality_code, birth_date,
                  current_stay_started_on, prior_stay_months, source_document_id)
                VALUES (?, (SELECT id FROM visa_types WHERE code = 'E-9'), '2027-03-01', 'PHL', '1995-08-15',
                  '2023-02-01', 12, ?)""", userId, documentId);
    }

    private void finishSteps(long userId, String... steps) {
        for (String step : steps) {
            jdbcTemplate.update("""
                    INSERT INTO onboarding_progress (user_id, step_code, status, completed_at)
                    VALUES (?, ?, 'COMPLETED', NOW(3))""", userId, step);
        }
    }

    private long upload(String bearer) throws Exception {
        ResultActions result = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("front", "front.jpg", "image/jpeg", ArcImages.jpeg(8 * 1024)))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isAccepted());
        return JsonMapper.builder().build().readTree(result.andReturn().getResponse().getContentAsString())
                .path("documentId").longValue();
    }

    /** worker 의 OCR 결과 보고(합성 데이터). */
    private void recordOcr(long documentId) {
        documentInternalService.recordOcrResult(documentId, new OcrResultRequest(documentId, "ppocrv5-ko-mobile-int8",
                842, DocumentSide.FRONT, List.of(
                new OcrFieldResult("nameEn", "MARK ANTHONY REYES", new BigDecimal("0.9812"), false),
                new OcrFieldResult("nationality", "PHL", new BigDecimal("0.9655"), false),
                new OcrFieldResult("birthDate", "1995-08-15", new BigDecimal("0.9734"), false),
                new OcrFieldResult("visaType", "E-9", new BigDecimal("0.95"), false),
                new OcrFieldResult("stayExpiry", null, null, false),
                new OcrFieldResult("registrationNo", "950815-5******", new BigDecimal("0.9907"), true))));
    }
}
