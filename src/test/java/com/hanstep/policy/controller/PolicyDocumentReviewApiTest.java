package com.hanstep.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.repository.PolicyDocumentRepository;
import jakarta.persistence.EntityManager;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** #53 검토 상태 변경, #42 이전 버전 정리. 실제 DB, 테스트마다 롤백. 관리자·문서는 테스트 안에서 새로 넣는다. */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class PolicyDocumentReviewApiTest {

    private static final String URL = "/api/v1/admin/policy-documents/{id}/review";
    private static final LocalDate TODAY = LocalDate.now(KstDates.KST);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PolicyDocumentRepository policyDocumentRepository;

    private long adminId;
    private String admin;

    @BeforeEach
    void setUp() {
        adminId = insertUser("test-policy-admin", "ADMIN");
        admin = "Bearer " + jwtTokenProvider.issue(adminId);
    }

    @Test
    void 관리자가_DRAFT를_승인하면_APPROVED가_되고_이력이_남는다() throws Exception {
        long id = insertDoc("DRAFT", null, null, null);
        jdbcTemplate.update("UPDATE policy_documents SET chunk_count = 9 WHERE id = ?", id);

        review(id, "{\"reviewStatus\": \"APPROVED\", \"effectiveFrom\": \"%s\", \"note\": \"원문 확인\"}"
                .formatted(TODAY.minusDays(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed.length()").value(1))
                .andExpect(jsonPath("$.changed[0].policyDocumentId").value(id))
                .andExpect(jsonPath("$.changed[0].previousStatus").value("DRAFT"))
                .andExpect(jsonPath("$.changed[0].reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.changed[0].effectiveFrom").value(TODAY.minusDays(1).toString()))
                .andExpect(jsonPath("$.changed[0].searchable").value(true))
                .andExpect(jsonPath("$.changed[0].reason").value("REVIEW"))
                .andExpect(jsonPath("$.warnings").isEmpty());

        Map<String, Object> row = docRow(id);
        assertThat(row.get("review_status")).isEqualTo("APPROVED");
        assertThat(row.get("effective_from").toString()).isEqualTo(TODAY.minusDays(1).toString());
        List<Map<String, Object>> reviews = reviews(id);
        assertThat(reviews).hasSize(1);
        Map<String, Object> history = reviews.get(0);
        assertThat(List.of(history.get("reason"), history.get("from_status"), history.get("to_status")))
                .containsExactly("REVIEW", "DRAFT", "APPROVED");
        assertThat(history.get("from_effective_from")).isNull();
        assertThat(history.get("to_effective_from").toString()).isEqualTo(TODAY.minusDays(1).toString());
        assertThat(((Number) history.get("reviewed_by")).longValue()).isEqualTo(adminId);
        assertThat(history.get("superseded_by")).isNull();
        assertThat(history.get("note")).isEqualTo("원문 확인");
    }

    @Test
    void 시행일이_없어도_승인할_수_있고_적재_전이면_경고를_준다() throws Exception {
        long id = insertDoc("NEEDS_REVIEW", null, null, null);

        review(id, "{\"reviewStatus\": \"APPROVED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed[0].effectiveFrom").isEmpty())
                .andExpect(jsonPath("$.changed[0].searchable").value(true))
                .andExpect(jsonPath("$.warnings[0]").value("NOT_INGESTED"));
    }

    @Test
    void 바꿀_수_없는_상태면_409이고_아무것도_바꾸지_않는다() throws Exception {
        long approved = insertDoc("APPROVED", null, null, null);
        long retired = insertDoc("RETIRED", null, null, null);
        long draft = insertDoc("DRAFT", null, null, null);

        for (Object[] c : new Object[][] {{approved, "DRAFT"}, {approved, "NEEDS_REVIEW"}, {approved, "APPROVED"},
                {retired, "APPROVED"}, {draft, "DRAFT"}}) {
            review((long) c[0], "{\"reviewStatus\": \"" + c[1] + "\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                    .andExpect(jsonPath("$.details.reason").value("INVALID_TRANSITION"));
        }
        assertThat(docRow(approved).get("review_status")).isEqualTo("APPROVED");
        assertThat(reviews(approved)).isEmpty();
    }

    @Test
    void 종료일이_시행일보다_늦지_않으면_400() throws Exception {
        long id = insertDoc("DRAFT", TODAY.plusDays(10), null, null);

        review(id, "{\"reviewStatus\": \"APPROVED\", \"effectiveTo\": \"%s\"}".formatted(TODAY.plusDays(10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("effectiveTo"));
        review(id, "{}").andExpect(status().isBadRequest());
        assertThat(docRow(id).get("review_status")).isEqualTo("DRAFT");
    }

    @Test
    void 관리자가_아니면_403_토큰이_없으면_401_없는_문서면_404() throws Exception {
        long id = insertDoc("DRAFT", null, null, null);
        long userId = insertUser("test-policy-user", "USER");

        mockMvc.perform(patch(URL, id).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewStatus\": \"APPROVED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(patch(URL, id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\": \"APPROVED\"}"))
                .andExpect(status().isUnauthorized());
        review(999_999_999L, "{\"reviewStatus\": \"APPROVED\"}").andExpect(status().isNotFound());
        assertThat(docRow(id).get("review_status")).isEqualTo("DRAFT");
    }

    // ---------- #42 이전 버전 정리 ----------

    @Test
    void 시행일이_늦은_새_버전을_승인하면_시행일_전날까지는_옛_버전만_시행일부터는_새_버전만_검색된다() throws Exception {
        LocalDate next = TODAY.plusDays(10);
        long old = insertLaw("LAW-T1", "APPROVED", TODAY.minusDays(100));
        long newer = insertLaw("LAW-T1", "DRAFT", next);

        review(newer, "{\"reviewStatus\": \"APPROVED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed.length()").value(2))
                .andExpect(jsonPath("$.changed[0].policyDocumentId").value(newer))
                .andExpect(jsonPath("$.changed[1].policyDocumentId").value(old))
                .andExpect(jsonPath("$.changed[1].reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.changed[1].effectiveTo").value(next.toString()))
                .andExpect(jsonPath("$.changed[1].reason").value("SUPERSEDED"));

        PolicyDocument oldDoc = reload(old);
        PolicyDocument newDoc = reload(newer);
        assertThat(List.of(oldDoc.isSearchable(next.minusDays(1)), newDoc.isSearchable(next.minusDays(1))))
                .containsExactly(true, false);
        assertThat(List.of(oldDoc.isSearchable(next), newDoc.isSearchable(next))).containsExactly(false, true);
        Map<String, Object> history = reviews(old).get(0);
        assertThat(List.of(history.get("reason"), history.get("from_status"), history.get("to_status")))
                .containsExactly("SUPERSEDED", "APPROVED", "APPROVED");
        assertThat(history.get("to_effective_to").toString()).isEqualTo(next.toString());
        assertThat(((Number) history.get("superseded_by")).longValue()).isEqualTo(newer);
    }

    @Test
    void 시행일이_같거나_없는_새_버전을_승인하면_옛_버전은_RETIRED() throws Exception {
        LocalDate day = TODAY.minusDays(5);
        long sameOld = insertLaw("LAW-T2", "APPROVED", day);
        long sameNew = insertLaw("LAW-T2", "DRAFT", day);
        long nullOld = insertLaw("LAW-T3", "APPROVED", TODAY.minusDays(50));
        long nullNew = insertLaw("LAW-T3", "DRAFT", null);

        review(sameNew, "{\"reviewStatus\": \"APPROVED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed[1].policyDocumentId").value(sameOld))
                .andExpect(jsonPath("$.changed[1].reviewStatus").value("RETIRED"))
                .andExpect(jsonPath("$.changed[1].searchable").value(false));
        review(nullNew, "{\"reviewStatus\": \"APPROVED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed[1].policyDocumentId").value(nullOld))
                .andExpect(jsonPath("$.changed[1].reviewStatus").value("RETIRED"));

        assertThat(List.of(docRow(sameOld).get("review_status"), docRow(nullOld).get("review_status")))
                .containsExactly("RETIRED", "RETIRED");
        // ck_policy_documents_period 때문에 종료일을 같은 날로 채우지 않는다
        assertThat(docRow(sameOld).get("effective_to")).isNull();
    }

    @Test
    void 새_시행일_전에_이미_끝난_버전과_externalId가_없는_문서는_건드리지_않는다() throws Exception {
        long ended = insertDoc("APPROVED", TODAY.minusDays(100), TODAY.minusDays(50), null, "LAW", "LAW-T4");
        long newer = insertLaw("LAW-T4", "DRAFT", TODAY.plusDays(10));
        long seedLike = insertDoc("APPROVED", null, null, null);
        long other = insertDoc("DRAFT", null, null, null);

        review(newer, "{\"reviewStatus\": \"APPROVED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed.length()").value(1));
        review(other, "{\"reviewStatus\": \"APPROVED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed.length()").value(1));

        assertThat(docRow(ended).get("effective_to").toString()).isEqualTo(TODAY.minusDays(50).toString());
        assertThat(docRow(seedLike).get("review_status")).isEqualTo("APPROVED");
        assertThat(reviews(ended)).isEmpty();
    }

    @Test
    void 시행일이_더_늦은_버전이_이미_승인돼_있으면_409() throws Exception {
        long later = insertLaw("LAW-T5", "APPROVED", TODAY.plusDays(30));
        long earlier = insertLaw("LAW-T5", "DRAFT", TODAY.plusDays(10));

        review(earlier, "{\"reviewStatus\": \"APPROVED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("NEWER_VERSION_APPROVED"))
                .andExpect(jsonPath("$.details.policyDocumentId").value(later));
        assertThat(List.of(docRow(later).get("review_status"), docRow(earlier).get("review_status")))
                .containsExactly("APPROVED", "DRAFT");
    }

    // ---------- helpers ----------

    private ResultActions review(long id, String body) throws Exception {
        return mockMvc.perform(patch(URL, id).header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // 테스트 트랜잭션 안이라 JPA 변경을 먼저 내려보내야 JDBC 로 보인다
    private Map<String, Object> docRow(long id) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("SELECT * FROM policy_documents WHERE id = ?", id);
    }

    private List<Map<String, Object>> reviews(long id) {
        return jdbcTemplate.queryForList("SELECT * FROM policy_document_reviews WHERE policy_document_id = ? "
                + "ORDER BY id", id);
    }

    private PolicyDocument reload(long id) {
        entityManager.flush();
        entityManager.clear();
        return policyDocumentRepository.findById(id).orElseThrow();
    }

    private long insertUser(String key, String role) {
        jdbcTemplate.update("INSERT INTO users (external_user_key, display_name, role) VALUES (?, 'TEST', ?)", key, role);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class, key);
    }

    private long insertLaw(String externalId, String status, LocalDate from) {
        return insertDoc(status, from, null, null, "LAW", externalId);
    }

    private long insertDoc(String status, LocalDate from, LocalDate to, String hash) {
        return insertDoc(status, from, to, hash, "NOTICE", null);
    }

    private long insertDoc(String status, LocalDate from, LocalDate to, String hash, String sourceType,
                           String externalId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO policy_documents (source_type, title, issuing_agency, source_url, external_id,
                      visa_type_code, effective_from, effective_to, policy_version, content_hash, review_status)
                    VALUES (?, '테스트 문서', '법무부', 'https://example.com', ?, 'E-7-4', ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sourceType);
            ps.setString(2, externalId);
            ps.setDate(3, from == null ? null : Date.valueOf(from));
            ps.setDate(4, to == null ? null : Date.valueOf(to));
            ps.setString(5, "TEST-" + UUID.randomUUID().toString().substring(0, 8));
            ps.setString(6, hash != null ? hash : randomHash());
            ps.setString(7, status);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private static String randomHash() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
