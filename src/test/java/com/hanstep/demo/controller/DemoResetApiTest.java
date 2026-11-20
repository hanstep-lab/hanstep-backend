package com.hanstep.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.demo.service.DemoResetService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 데모 계정 초기화(POST /api/v1/demo/reset, #131). 켜진 상태(hanstep.demo.reset-enabled=true).
 * 실제 DB, 테스트마다 롤백. R2 대신 메모리 저장소. 데모 시드 사용자 대신 demo- 로 시작하는 테스트 사용자를 만든다.
 * 비자 프로필 복원만 시드 사용자(Reyes)로 확인한다(롤백되므로 시드 데이터는 바뀌지 않음).
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
                .andExpect(jsonPath("$.failedOriginals").value(0))
                // Reyes 가 아닌 데모 계정은 프로필을 되돌리지 않는다
                .andExpect(jsonPath("$.profileRestored").value(false));

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
                .andExpect(jsonPath("$.failedOriginals").value(0))
                .andExpect(jsonPath("$.alertsCancelled").value(0))
                .andExpect(jsonPath("$.profileRestored").value(false))
                .andExpect(jsonPath("$.e74AssessmentsDeleted").value(0))
                .andExpect(jsonPath("$.roadmapsDeleted").value(0))
                .andExpect(jsonPath("$.languageRestored").value(false))
                .andExpect(jsonPath("$.goalsCancelled").value(0))
                .andExpect(jsonPath("$.commitmentsDeactivated").value(0));
    }

    @Test
    void 매달_나가는_돈은_비활성으로_바꿔_맞춤_질문이_빈_값으로_시작한다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "DEMO USER");
        String bearer = bearer(userId);
        // 맞춤 질문에서 고정생활비·변동지출을 입력한 상태. 이미 비활성인 행은 세지 않는다
        insertCommitment(userId, "LIVING_MINIMUM", 1_250_000, true);
        insertCommitment(userId, "VARIABLE_EXPENSE", 1_000_000, true);
        insertCommitment(userId, "RENT", 400_000, false);

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitmentsDeactivated").value(2));

        // 행은 남는다
        assertThat(count("SELECT COUNT(*) FROM monthly_commitments WHERE user_id = ?", userId)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM monthly_commitments WHERE user_id = ? AND is_active", userId)).isZero();
        mockMvc.perform(get("/api/v1/commitments").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totals.fixedLiving.amount").value(0));
    }

    @Test
    void ACTIVE_금융_목표는_CANCELLED로_바꿔_목표_선택이_체크_없이_시작한다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "DEMO USER");
        String bearer = bearer(userId);
        // 온보딩에서 목돈 마련·본국 송금을 고른 상태
        long lumpSum = insertGoal(userId, "LUMP_SUM", "ACTIVE");
        long remittance = insertGoal(userId, "REMITTANCE", "ACTIVE");
        long achieved = insertGoal(userId, "CREDIT", "ACHIEVED");
        mockMvc.perform(get("/api/v1/goals").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.goals.length()").value(2));

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsCancelled").value(2));

        // 행은 남고 CANCELLED, 달성한 목표는 그대로
        assertThat(goalStatus(lumpSum)).isEqualTo("CANCELLED");
        assertThat(goalStatus(remittance)).isEqualTo("CANCELLED");
        assertThat(goalStatus(achieved)).isEqualTo("ACHIEVED");
        mockMvc.perform(get("/api/v1/goals").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals.length()").value(0));
    }

    @Test
    void Reyes가_아닌_데모_계정은_언어를_건드리지_않는다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "DEMO USER");
        jdbcTemplate.update("UPDATE users SET preferred_language = 'ko' WHERE id = ?", userId);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.languageRestored").value(false));

        assertThat(jdbcTemplate.queryForObject("SELECT preferred_language FROM users WHERE id = ?", String.class,
                userId)).isEqualTo("ko");
    }

    @Test
    void ACTIVE_알림은_CANCELLED로_바꾸고_같은_조건으로_다시_만들_수_있다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "DEMO USER");
        String bearer = bearer(userId);
        insertRate("XTS");
        String alert = "{\"currency\":\"XTS\",\"rateType\":\"SEND\",\"comparator\":\"LTE\",\"targetRate\":1350}";
        createAlert(bearer, alert).andExpect(status().isCreated());
        createAlert(bearer, alert).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        long triggered = insertAlert(userId, "GTE", "TRIGGERED");

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsCancelled").value(1));

        assertThat(count("SELECT COUNT(*) FROM fx_alerts WHERE user_id = ? AND status = 'ACTIVE'", userId)).isZero();
        // 행은 남는다. 도달한 알림은 그대로
        assertThat(count("SELECT COUNT(*) FROM fx_alerts WHERE user_id = ? AND status = 'CANCELLED'", userId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fx_alerts WHERE id = ?", String.class, triggered))
                .isEqualTo("TRIGGERED");

        createAlert(bearer, alert).andExpect(status().isCreated());
    }

    @Test
    void E74_평가와_로드맵을_지운다() throws Exception {
        long userId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "DEMO USER");
        long assessmentId = insertAssessment(userId, "first");
        long scenarioId = insertAssessment(userId, "scenario");
        long roadmapId = insertRoadmap(userId, assessmentId, scenarioId);

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.e74AssessmentsDeleted").value(2))
                .andExpect(jsonPath("$.roadmapsDeleted").value(1));

        assertThat(count("SELECT COUNT(*) FROM e74_assessments WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM e74_assessment_items WHERE assessment_id = ?", assessmentId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM visa_roadmaps WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM visa_roadmap_steps WHERE roadmap_id = ?", roadmapId)).isZero();
    }

    @Test
    void Reyes는_비자_프로필을_시드_값으로_되돌리고_입국일과_이전_체류_개월은_그대로_둔다() throws Exception {
        long reyesId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class,
                DemoResetService.ReyesSeed.EXTERNAL_USER_KEY);
        Map<String, Object> before = jdbcTemplate.queryForMap(
                "SELECT current_stay_started_on, prior_stay_months FROM visa_profiles WHERE user_id = ?", reyesId);
        // 샘플 등록증으로 DOC-04 확정한 것처럼 덮어쓴다
        long documentId = insertConfirmedDocument(reyesId);
        jdbcTemplate.update("""
                UPDATE visa_profiles SET visa_type_id = (SELECT id FROM visa_types WHERE code = 'H-2'),
                  stay_expires_on = '2028-01-31', nationality_code = 'VNM', birth_date = '1990-01-01',
                  source_document_id = ?, evidence_status = 'DECLARED' WHERE user_id = ?""", documentId, reyesId);
        // 온보딩 언어 화면에서 ko 로 바꾼 것처럼
        jdbcTemplate.update("UPDATE users SET preferred_language = 'ko' WHERE id = ?", reyesId);
        insertAssessment(reyesId, "reyes");
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer(reyesId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileRestored").value(true))
                .andExpect(jsonPath("$.languageRestored").value(true));

        assertThat(jdbcTemplate.queryForObject("SELECT preferred_language FROM users WHERE id = ?", String.class,
                reyesId)).isEqualTo(DemoResetService.ReyesSeed.PREFERRED_LANGUAGE);

        Map<String, Object> profile = jdbcTemplate.queryForMap("""
                SELECT vt.code, vp.stay_expires_on, vp.nationality_code, vp.birth_date, vp.source_document_id,
                  vp.evidence_status, vp.current_stay_started_on, vp.prior_stay_months
                FROM visa_profiles vp JOIN visa_types vt ON vt.id = vp.visa_type_id WHERE vp.user_id = ?""", reyesId);
        assertThat(profile.get("code")).isEqualTo(DemoResetService.ReyesSeed.VISA_TYPE_CODE);
        assertThat(profile.get("stay_expires_on").toString()).isEqualTo(DemoResetService.ReyesSeed.STAY_EXPIRES_ON);
        assertThat(profile.get("nationality_code")).isEqualTo(DemoResetService.ReyesSeed.NATIONALITY_CODE);
        assertThat(profile.get("birth_date").toString()).isEqualTo(DemoResetService.ReyesSeed.BIRTH_DATE);
        assertThat(profile.get("source_document_id")).isNull();
        assertThat(profile.get("evidence_status")).isEqualTo(DemoResetService.ReyesSeed.EVIDENCE_STATUS);
        assertThat(profile.get("current_stay_started_on")).isEqualTo(before.get("current_stay_started_on"));
        assertThat(profile.get("prior_stay_months")).isEqualTo(before.get("prior_stay_months"));
        assertThat(count("SELECT COUNT(*) FROM e74_assessments WHERE user_id = ?", reyesId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM visa_roadmaps WHERE user_id = ?", reyesId)).isZero();
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
        long alertId = insertAlert(userId, "LTE", "ACTIVE");
        insertAssessment(userId, "not-demo");
        long goalId = insertGoal(userId, "LUMP_SUM", "ACTIVE");
        insertCommitment(userId, "RENT", 400_000, true);

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(goalStatus(goalId)).isEqualTo("ACTIVE");
        assertThat(count("SELECT COUNT(*) FROM monthly_commitments WHERE user_id = ? AND is_active", userId))
                .isEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM documents WHERE id = ?", documentId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM onboarding_progress WHERE user_id = ?", userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fx_alerts WHERE id = ?", String.class, alertId))
                .isEqualTo("ACTIVE");
        assertThat(count("SELECT COUNT(*) FROM e74_assessments WHERE user_id = ?", userId)).isEqualTo(1);
    }

    @Test
    void 다른_사용자의_데이터는_지우지_않는다() throws Exception {
        long demoId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "DEMO USER");
        long otherId = TestUsers.create(jdbcTemplate, "demo-test-" + UUID.randomUUID(), "OTHER DEMO USER");
        long otherDocument = insertConfirmedDocument(otherId);
        finishSteps(otherId, "LANGUAGE");
        insertAiJob(otherId, "OCR");
        insertAiJob(demoId, "OCR");
        long otherAlert = insertAlert(otherId, "LTE", "ACTIVE");
        insertRoadmap(otherId, insertAssessment(otherId, "other"), null);
        long otherGoal = insertGoal(otherId, "REMITTANCE", "ACTIVE");
        insertCommitment(otherId, "RENT", 400_000, true);

        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer(demoId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedOcrJobs").value(1))
                .andExpect(jsonPath("$.alertsCancelled").value(0))
                .andExpect(jsonPath("$.e74AssessmentsDeleted").value(0))
                .andExpect(jsonPath("$.roadmapsDeleted").value(0))
                .andExpect(jsonPath("$.goalsCancelled").value(0))
                .andExpect(jsonPath("$.commitmentsDeactivated").value(0));
        assertThat(goalStatus(otherGoal)).isEqualTo("ACTIVE");
        assertThat(count("SELECT COUNT(*) FROM monthly_commitments WHERE user_id = ? AND is_active", otherId))
                .isEqualTo(1);
        assertThat(count(OCR_JOBS, otherId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fx_alerts WHERE id = ?", String.class, otherAlert))
                .isEqualTo("ACTIVE");
        assertThat(count("SELECT COUNT(*) FROM e74_assessments WHERE user_id = ?", otherId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM visa_roadmaps WHERE user_id = ?", otherId)).isEqualTo(1);

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

    /** 알림을 만들 때 단위(unit_amount)를 읽는 환율 행. */
    private void insertRate(String currency) {
        jdbcTemplate.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                + "source, is_mock, fetched_at) VALUES (?, 1, '2099-06-01', '2099-05-31 15:00:00', 1300.0000, "
                + "1313.0000, 'KOREAEXIM', FALSE, NOW(3))", currency);
    }

    private ResultActions createAlert(String bearer, String body) throws Exception {
        ResultActions result = mockMvc.perform(post("/api/v1/fx/alerts").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body));
        // 초기화는 JDBC 로 바꾸므로 요청 경계처럼 영속성 컨텍스트를 비운다
        entityManager.flush();
        entityManager.clear();
        return result;
    }

    private long insertAlert(long userId, String comparator, String status) {
        jdbcTemplate.update("""
                INSERT INTO fx_alerts (user_id, currency, comparator, target_rate, status, triggered_at)
                VALUES (?, 'XTS', ?, 1350.0000, ?, IF(? = 'TRIGGERED', NOW(3), NULL))""",
                userId, comparator, status, status);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 평가 1건과 항목 1건. 규칙셋은 기준 시드의 첫 행. */
    private long insertAssessment(long userId, String requestKey) {
        jdbcTemplate.update("""
                INSERT INTO e74_assessments (user_id, rule_set_id, request_key, as_of_date, input_snapshot,
                  eligibility_status)
                SELECT ?, id, ?, CURDATE(), JSON_OBJECT(), 'NEEDS_EVIDENCE' FROM e74_rule_sets ORDER BY id LIMIT 1""",
                userId, requestKey + "-" + UUID.randomUUID());
        long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO e74_assessment_items (assessment_id, rule_code, category, status, earned_points)
                VALUES (?, 'TEST_RULE', 'BONUS', 'MET', 10)""", id);
        return id;
    }

    /** 로드맵 1건과 단계 1건. scenarioAssessmentId 는 단계의 시나리오 평가(없으면 null). */
    private long insertRoadmap(long userId, long assessmentId, Long scenarioAssessmentId) {
        jdbcTemplate.update("INSERT INTO visa_roadmaps (user_id, assessment_id, stay_expires_on) VALUES (?, ?, "
                + "'2027-10-04')", userId, assessmentId);
        long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO visa_roadmap_steps (roadmap_id, step_order, title, scenario_assessment_id)
                VALUES (?, 1, 'TEST STEP', ?)""", id, scenarioAssessmentId);
        return id;
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

    private long insertGoal(long userId, String goalType, String status) {
        jdbcTemplate.update("INSERT INTO financial_goals (user_id, goal_type, status) VALUES (?, ?, ?)", userId,
                goalType, status);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertCommitment(long userId, String type, long amount, boolean active) {
        jdbcTemplate.update("INSERT INTO monthly_commitments (user_id, commitment_type, amount, is_active) "
                + "VALUES (?, ?, ?, ?)", userId, type, amount, active);
    }

    private String goalStatus(long goalId) {
        return jdbcTemplate.queryForObject("SELECT status FROM financial_goals WHERE id = ?", String.class, goalId);
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
