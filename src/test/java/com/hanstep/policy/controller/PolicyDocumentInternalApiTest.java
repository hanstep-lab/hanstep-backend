package com.hanstep.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.time.KstDates;
import java.sql.Date;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

/** AI-R1, INT-13, 상태 목록. 실제 DB, 테스트마다 롤백. 문서는 테스트 안에서 새로 넣는다. */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class PolicyDocumentInternalApiTest {

    private static final String ELIGIBILITY_URL = "/internal/v1/ai-context/policy-documents/search-eligibility";
    private static final String STATES_URL = "/internal/v1/ai-context/policy-documents";
    private static final LocalDate TODAY = LocalDate.now(KstDates.KST);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    // ---------- AI-R1 ----------

    @Test
    void 승인되고_시행_기간_안이면_searchable() throws Exception {
        long id = insertDoc("APPROVED", TODAY.minusDays(10), TODAY.plusDays(10), null);

        eligibility(String.valueOf(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].policyDocumentId").value(id))
                .andExpect(jsonPath("$.documents[0].reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.documents[0].effectiveFrom").value(TODAY.minusDays(10).toString()))
                .andExpect(jsonPath("$.documents[0].effectiveTo").value(TODAY.plusDays(10).toString()))
                .andExpect(jsonPath("$.documents[0].searchable").value(true));
    }

    @Test
    void 시행일이_NULL이어도_승인되면_searchable이고_effectiveFrom은_null() throws Exception {
        long id = insertDoc("APPROVED", null, null, null);

        eligibility(String.valueOf(id))
                .andExpect(jsonPath("$.documents[0].searchable").value(true))
                .andExpect(jsonPath("$.documents[0].effectiveFrom").isEmpty());
    }

    @Test
    void 승인_전이거나_은퇴했거나_시행_기간_밖이면_searchable_아님() throws Exception {
        long draft = insertDoc("DRAFT", null, null, null);
        long retired = insertDoc("RETIRED", null, null, null);
        long needsReview = insertDoc("NEEDS_REVIEW", null, null, null);
        long notYet = insertDoc("APPROVED", TODAY.plusDays(1), null, null);
        long endsToday = insertDoc("APPROVED", TODAY.minusDays(30), TODAY, null); // 종료일 미포함

        eligibility(draft + "," + retired + "," + needsReview + "," + notYet + "," + endsToday)
                .andExpect(jsonPath("$.documents.length()").value(5))
                .andExpect(jsonPath("$.documents[?(@.searchable == true)]").isEmpty());
    }

    @Test
    void 시행일이_오늘이면_searchable() throws Exception {
        long id = insertDoc("APPROVED", TODAY, null, null);

        eligibility(String.valueOf(id)).andExpect(jsonPath("$.documents[0].searchable").value(true));
    }

    @Test
    void 없는_ID는_404가_아니라_목록에서_빠진다() throws Exception {
        long id = insertDoc("APPROVED", null, null, null);

        eligibility(id + ",999999999, " + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].policyDocumentId").value(id));
    }

    @Test
    void ids가_비었거나_숫자가_아니거나_20개를_넘으면_400() throws Exception {
        String twentyOne = IntStream.rangeClosed(1, 21).mapToObj(String::valueOf).collect(Collectors.joining(","));
        for (String ids : new String[] {"", "abc", "1,,2", "-1", "0", "1.5", twentyOne}) {
            eligibility(ids)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details.fields[0].field").value("ids"));
        }
        mockMvc.perform(get(ELIGIBILITY_URL).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ids가_20개면_통과() throws Exception {
        String twenty = IntStream.rangeClosed(1, 20).mapToObj(String::valueOf).collect(Collectors.joining(","));
        eligibility(twenty).andExpect(status().isOk());
    }

    // ---------- 상태 목록(AI 상태 대조) ----------

    @Test
    void 상태_목록은_모든_행을_id_순으로_스냅샷_필드만_준다() throws Exception {
        String hash = randomHash();
        long ingested = insertDoc("APPROVED", null, null, hash, "E74-STATE-v1", null);
        jdbcTemplate.update("UPDATE policy_documents SET chunk_count = 12 WHERE id = ?", ingested);
        long notIngested = insertDoc("DRAFT", null, null, null);

        JsonNode documents = states().path("documents");

        assertThat(documents.size())
                .isEqualTo(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM policy_documents", Integer.class));
        List<Long> ids = new ArrayList<>();
        for (JsonNode doc : documents) {
            // AI 스냅샷 모델은 모르는 필드를 거부한다(app/rag/reconcile.py SnapshotDocument)
            assertThat(doc.propertyNames()).containsExactlyInAnyOrder("policyDocumentId", "policyVersion",
                    "contentHash", "reviewStatus", "searchable", "chunkCount");
            ids.add(doc.path("policyDocumentId").asLong());
        }
        assertThat(ids).isSorted();
        JsonNode doc = find(documents, ingested);
        assertThat(doc.path("policyVersion").stringValue()).isEqualTo("E74-STATE-v1");
        assertThat(doc.path("contentHash").stringValue()).isEqualTo(hash);
        assertThat(doc.path("reviewStatus").stringValue()).isEqualTo("APPROVED");
        assertThat(doc.path("chunkCount").intValue()).isEqualTo(12);
        assertThat(find(documents, notIngested).path("chunkCount").isNull()).isTrue();
    }

    @Test
    void 상태_목록의_searchable은_검색_가능_여부와_같은_판정() throws Exception {
        Map<Long, Boolean> expected = Map.of(
                insertDoc("APPROVED", TODAY.minusDays(10), TODAY.plusDays(10), null), true,
                insertDoc("APPROVED", null, null, null), true,
                insertDoc("APPROVED", TODAY, null, null), true,
                insertDoc("APPROVED", TODAY.plusDays(1), null, null), false,
                insertDoc("APPROVED", TODAY.minusDays(30), TODAY, null), false, // 종료일 미포함
                insertDoc("DRAFT", null, null, null), false,
                insertDoc("NEEDS_REVIEW", null, null, null), false,
                insertDoc("RETIRED", null, null, null), false);

        JsonNode documents = states().path("documents");

        expected.forEach((id, searchable) -> assertThat(find(documents, id).path("searchable").booleanValue())
                .as("document %d", id).isEqualTo(searchable));
    }

    @Test
    void 상태_목록이_1000행을_넘으면_잘라서_주지_않고_409() throws Exception {
        int existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM policy_documents", Integer.class);
        List<Object[]> rows = new ArrayList<>();
        for (int i = existing; i <= 1000; i++) {
            rows.add(new Object[] {"BULK-" + i, randomHash()});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO policy_documents (source_type, title, issuing_agency, source_url, visa_type_code,
                  policy_version, content_hash)
                VALUES ('NOTICE', '테스트 문서', '법무부', 'https://example.com', 'E-7-4', ?, ?)""", rows);

        mockMvc.perform(get(STATES_URL).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.details.reason").value("TOO_MANY_DOCUMENTS"))
                .andExpect(jsonPath("$.details.limit").value(1000))
                .andExpect(jsonPath("$.details.total").value(1001))
                .andExpect(jsonPath("$.documents").doesNotExist());
    }

    @Test
    void 상태_목록도_토큰이_없으면_401() throws Exception {
        mockMvc.perform(get(STATES_URL)).andExpect(status().isUnauthorized());
    }

    // ---------- INT-13 ----------

    @Test
    void 인덱싱_완료를_기록하고_승인_상태는_바꾸지_않는다() throws Exception {
        String hash = randomHash();
        long id = insertDoc("DRAFT", null, null, hash, "E74-TEST-v1", null);

        ingested(id, ingestedBody("E74-TEST-v1", hash, "policy-sources/visa/sha256/" + hash + ".pdf"))
                .andExpect(status().isNoContent());

        Map<String, Object> row = row(id);
        assertThat(row.get("chunk_count")).isEqualTo(7L);
        assertThat(row.get("chroma_collection")).isEqualTo("visa_policy_chunks");
        assertThat(row.get("embedding_model")).isEqualTo("BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181");
        assertThat(row.get("ingested_at")).isNotNull();
        assertThat(row.get("object_key")).isEqualTo("policy-sources/visa/sha256/" + hash + ".pdf");
        assertThat(row.get("review_status")).isEqualTo("DRAFT");
    }

    @Test
    void object_key가_이미_있으면_덮어쓰지_않는다() throws Exception {
        String hash = randomHash();
        long id = insertDoc("APPROVED", null, null, hash, "E74-TEST-v1", "seed/original.pdf");

        ingested(id, ingestedBody("E74-TEST-v1", hash, "policy-sources/other.pdf")).andExpect(status().isNoContent());

        assertThat(row(id).get("object_key")).isEqualTo("seed/original.pdf");
        assertThat(row(id).get("review_status")).isEqualTo("APPROVED");
    }

    @Test
    void 같은_보고를_다시_보내면_204() throws Exception {
        String hash = randomHash();
        long id = insertDoc("DRAFT", null, null, hash, "E74-TEST-v1", null);
        String body = ingestedBody("E74-TEST-v1", hash, null);

        ingested(id, body).andExpect(status().isNoContent());
        ingested(id, body).andExpect(status().isNoContent());

        assertThat(row(id).get("chunk_count")).isEqualTo(7L);
        assertThat(row(id).get("object_key")).isNull();
    }

    @Test
    void 없는_문서면_404() throws Exception {
        ingested(999999999L, ingestedBody("E74-TEST-v1", randomHash(), null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 버전이나_해시가_다르면_409() throws Exception {
        String hash = randomHash();
        long id = insertDoc("DRAFT", null, null, hash, "E74-TEST-v1", null);

        ingested(id, ingestedBody("E74-TEST-v2", hash, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        ingested(id, ingestedBody("E74-TEST-v1", randomHash(), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertThat(row(id).get("ingested_at")).isNull();
    }

    @Test
    void 본문_형식이_틀리면_400() throws Exception {
        long id = insertDoc("DRAFT", null, null, null);

        ingested(id, ingestedBody("E74-TEST-v1", "NOT-A-HASH", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        ingested(id, "{\"documentVersion\":\"E74-TEST-v1\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(post("/internal/v1/ai/policies/1/ingested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestedBody("x", randomHash(), null)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private JsonNode states() throws Exception {
        String body = mockMvc.perform(get(STATES_URL).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = MAPPER.readTree(body);
        // AI 스냅샷 모델은 documents 외 필드도 거부한다(app/rag/reconcile.py Snapshot)
        assertThat(root.propertyNames()).containsExactly("documents");
        return root;
    }

    private static JsonNode find(JsonNode documents, long id) {
        for (JsonNode doc : documents) {
            if (doc.path("policyDocumentId").asLong() == id) {
                return doc;
            }
        }
        throw new AssertionError("document " + id + " is not in the list");
    }

    private ResultActions eligibility(String ids) throws Exception {
        return mockMvc.perform(get(ELIGIBILITY_URL).param("ids", ids)
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY));
    }

    private ResultActions ingested(long id, String body) throws Exception {
        return mockMvc.perform(post("/internal/v1/ai/policies/{id}/ingested", id)
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String ingestedBody(String version, String hash, String objectKey) {
        return """
                {"documentVersion":"%s","contentHash":"%s","chunkCount":7,
                 "mongoCollection":"visa_policy_chunks","embeddingModel":"BAAI/bge-m3",
                 "embeddingRevision":"5617a9f61b028005a4858fdac845db406aefb181",
                 "objectKey":%s,"ingestedAt":"2026-11-10T10:03:12+09:00"}
                """.formatted(version, hash, objectKey == null ? "null" : "\"" + objectKey + "\"");
    }

    private long insertDoc(String status, LocalDate from, LocalDate to, String hash) {
        return insertDoc(status, from, to, hash != null ? hash : randomHash(), "TEST-" + UUID.randomUUID().toString()
                .substring(0, 8), null);
    }

    private long insertDoc(String status, LocalDate from, LocalDate to, String hash, String version, String objectKey) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO policy_documents (source_type, title, issuing_agency, source_url, visa_type_code,
                      effective_from, effective_to, policy_version, content_hash, object_key, review_status)
                    VALUES ('NOTICE', '테스트 문서', '법무부', 'https://example.com', 'E-7-4', ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setDate(1, from == null ? null : Date.valueOf(from));
            ps.setDate(2, to == null ? null : Date.valueOf(to));
            ps.setString(3, version);
            ps.setString(4, hash);
            ps.setString(5, objectKey);
            ps.setString(6, status);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    // 테스트 트랜잭션 안이라 JPA 변경을 먼저 내려보내야 JDBC 로 보인다
    private Map<String, Object> row(long id) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("SELECT * FROM policy_documents WHERE id = ?", id);
    }

    private static String randomHash() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
