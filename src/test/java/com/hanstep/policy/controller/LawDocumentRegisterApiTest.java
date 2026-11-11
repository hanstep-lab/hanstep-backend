package com.hanstep.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.time.KstDates;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** AI-R6. 실제 DB, 테스트마다 롤백. 법령ID는 테스트마다 새로 만들어 seed·다른 테스트와 겹치지 않게 한다. */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class LawDocumentRegisterApiTest {

    private static final String URL = "/internal/v1/ai-context/policy-documents";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private final String externalId = String.valueOf(100_000_000 + RANDOM.nextInt(900_000_000));

    @Test
    void 새_법령을_등록하면_201과_DRAFT_행() throws Exception {
        String hash = randomHash();

        long id = idOf(register(hash, body(hash, "\"2026-10-23\"", "\"2026-10-23\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyVersion").value("LAW-" + externalId + "-20261023"))
                .andExpect(jsonPath("$.reviewStatus").value("DRAFT")));

        Map<String, Object> row = row(id);
        assertThat(row.get("source_type")).isEqualTo("LAW");
        assertThat(row.get("title")).isEqualTo("출입국관리법 시행규칙");
        assertThat(row.get("external_id")).isEqualTo(externalId);
        assertThat(row.get("content_hash")).isEqualTo(hash);
        assertThat(row.get("review_status")).isEqualTo("DRAFT");
        assertThat(row.get("object_key")).isNull();
    }

    @Test
    void 시행일이_없으면_공포일로_버전을_만든다() throws Exception {
        String hash = randomHash();

        register(hash, body(hash, "\"2026-10-01\"", "null"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyVersion").value("LAW-" + externalId + "-20261001"));
    }

    @Test
    void 시행일과_공포일이_모두_없으면_오늘_KST로_버전을_만든다() throws Exception {
        String hash = randomHash();
        String today = LocalDate.now(KstDates.KST).format(DateTimeFormatter.BASIC_ISO_DATE);

        register(hash, body(hash, "null", "null"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyVersion").value("LAW-" + externalId + "-" + today));
    }

    @Test
    void 같은_법령_같은_날짜에_해시가_다르면_해시_앞_6자를_붙인다() throws Exception {
        String first = randomHash();
        register(first, body(first, "null", "\"2026-10-23\"")).andExpect(status().isCreated());
        String second = randomHash();

        register(second, body(second, "null", "\"2026-10-23\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyVersion")
                        .value("LAW-" + externalId + "-20261023-" + second.substring(0, 6)));
    }

    @Test
    void 같은_해시로_다시_보내면_200이고_같은_행() throws Exception {
        String hash = randomHash();
        long id = idOf(register(hash, body(hash, "null", "\"2026-10-23\"")).andExpect(status().isCreated()));

        register(hash, body(hash, "null", "\"2026-10-23\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyDocumentId").value(id));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM policy_documents WHERE content_hash = ?", Integer.class, hash);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void Idempotency_Key가_contentHash와_다르면_400() throws Exception {
        String hash = randomHash();

        register(randomHash(), body(hash, "null", "null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("Idempotency-Key"));
    }

    @Test
    void Idempotency_Key가_없으면_400() throws Exception {
        String hash = randomHash();

        mockMvc.perform(post(URL)
                        .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(hash, "null", "null")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sourceType이_LAW가_아니면_400() throws Exception {
        String hash = randomHash();

        register(hash, body(hash, "null", "null").replace("\"LAW\"", "\"NOTICE\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("sourceType"));
    }

    @Test
    void 법령ID에_영숫자_외_문자가_있으면_400() throws Exception {
        String hash = randomHash();

        register(hash, body(hash, "null", "null").replace("\"" + externalId + "\"", "\"008494/1\""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 제목이_없으면_400() throws Exception {
        String hash = randomHash();

        register(hash, body(hash, "null", "null").replace("\"출입국관리법 시행규칙\"", "null"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        String hash = randomHash();

        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", hash)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(hash, "null", "null")))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private ResultActions register(String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post(URL)
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // publishedOn·effectiveFrom 은 JSON 값 그대로("\"2026-10-23\"" 또는 "null")
    private String body(String hash, String publishedOn, String effectiveFrom) {
        return """
                {"sourceType":"LAW","title":"출입국관리법 시행규칙","issuingAgency":"법무부",
                 "sourceUrl":"https://www.law.go.kr/법령/출입국관리법시행규칙","externalId":"%s","visaTypeCode":null,
                 "publishedOn":%s,"effectiveFrom":%s,"contentHash":"%s","objectKey":null}
                """.formatted(externalId, publishedOn, effectiveFrom, hash);
    }

    private static long idOf(ResultActions result) throws Exception {
        Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.policyDocumentId");
        return id.longValue();
    }

    // 테스트 트랜잭션 안이라 JPA 변경을 먼저 내려보내야 JDBC 로 보인다
    private Map<String, Object> row(long id) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("SELECT * FROM policy_documents WHERE id = ?", id);
    }

    private static String randomHash() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
