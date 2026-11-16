package com.hanstep.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.document.ArcImages;
import com.hanstep.document.FakeArcStorageConfig;
import com.hanstep.document.FakeArcStorageConfig.FakeArcImageStorage;
import com.hanstep.support.TestUsers;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 등록증 업로드(DOC-01). 실제 DB, 테스트마다 롤백(커밋하지 않으므로 OCR 작업은 발행되지 않고 NOT_PUBLISHED 로 보인다).
 * R2 대신 메모리 저장소. 커밋·발행·롤백 정리는 DocumentUploadCommitTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FakeArcStorageConfig.class)
class DocumentApiTest {

    static final String URL = "/api/v1/documents";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private FakeArcImageStorage storage;

    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        storage.reset();
        userId = TestUsers.create(jdbcTemplate, "test-doc-" + UUID.randomUUID(), "MARK ANTHONY REYES");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    @Test
    void 앞면을_올리면_202이고_R2_키_문서_OCR_작업을_만든다() throws Exception {
        byte[] image = ArcImages.jpeg(64 * 1024);

        JsonNode body = json(upload("front.jpg", "image/jpeg", image)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentType").value("ARC"))
                .andExpect(jsonPath("$.side").value("FRONT"))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.confirmedAt").doesNotExist()));
        long documentId = body.path("documentId").longValue();

        Map<String, Object> doc = jdbcTemplate.queryForMap("SELECT * FROM documents WHERE id = ?", documentId);
        String key = "arc/" + userId + "/" + documentId + "/front.jpg";
        assertThat(((Number) doc.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(doc.get("object_key")).isEqualTo(key);
        assertThat(doc.get("content_type")).isEqualTo("image/jpeg");
        assertThat(((Number) doc.get("file_size_bytes")).intValue()).isEqualTo(image.length);
        assertThat(doc.get("sha256")).isEqualTo(sha256(image));
        assertThat(doc.get("status")).isEqualTo("UPLOADED");
        // 원본 보관 기한 = 업로드 + 24시간(초 단위 오차는 저장 시각과 created_at 의 차이)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(MINUTE, created_at, retention_until) FROM documents WHERE id = ?",
                Integer.class, documentId)).isBetween(1439, 1440);
        assertThat(storage.objects.get(key)).isEqualTo(image);

        Map<String, Object> job = jdbcTemplate.queryForMap(
                "SELECT * FROM ai_jobs WHERE job_type = 'OCR' AND resource_type = 'DOCUMENT' AND resource_id = ?",
                documentId);
        assertThat(job.get("idempotency_key")).isEqualTo("document:" + documentId);
        assertThat(((Number) job.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(body.path("ocrJob").path("jobId").longValue()).isEqualTo(((Number) job.get("id")).longValue());
        assertThat(body.path("ocrJob").path("status").stringValue()).isEqualTo("PENDING");
        // 원본 위치·해시는 응답에 없다
        assertThat(body.toString()).doesNotContain(key, sha256(image));
        assertThat(body.path("uploadedAt").stringValue()).endsWith("+09:00");
    }

    @Test
    void 같은_사진을_다시_올리면_200이고_새로_저장하거나_작업을_만들지_않는다() throws Exception {
        byte[] image = ArcImages.jpeg(32 * 1024);
        long first = json(upload("front.jpg", "image/jpeg", image).andExpect(status().isAccepted()))
                .path("documentId").longValue();

        long again = json(upload("other-name.jpg", "image/jpeg", image).andExpect(status().isOk()))
                .path("documentId").longValue();

        assertThat(again).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM documents WHERE user_id = ?", userId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ai_jobs WHERE job_type = 'OCR' AND resource_id = ?", first)).isEqualTo(1);
        assertThat(storage.puts.get()).isEqualTo(1);
    }

    @Test
    void 다른_사용자가_같은_사진을_올리면_따로_저장한다() throws Exception {
        byte[] image = ArcImages.jpeg(16 * 1024);
        long mine = json(upload("front.jpg", "image/jpeg", image).andExpect(status().isAccepted()))
                .path("documentId").longValue();
        long otherId = TestUsers.create(jdbcTemplate, "test-doc-" + UUID.randomUUID(), "OTHER USER");

        long theirs = json(mockMvc.perform(multipart(URL).file(new MockMultipartFile("file", "f.jpg", "image/jpeg", image))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(otherId)))
                .andExpect(status().isAccepted())).path("documentId").longValue();

        assertThat(theirs).isNotEqualTo(mine);
        assertThat(storage.objects).containsKeys("arc/" + userId + "/" + mine + "/front.jpg",
                "arc/" + otherId + "/" + theirs + "/front.jpg");
    }

    @Test
    void PNG는_png_키로_저장한다() throws Exception {
        long documentId = json(upload("front.png", "image/png", ArcImages.png(8 * 1024))
                .andExpect(status().isAccepted())).path("documentId").longValue();

        assertThat(jdbcTemplate.queryForObject("SELECT object_key FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo("arc/" + userId + "/" + documentId + "/front.png");
    }

    @Test
    void 이미지가_아니면_400이고_R2에_올리지_않는다() throws Exception {
        expectInvalidFile(upload("front.jpg", "image/jpeg", ArcImages.random(4096)), "content does not match image/jpeg");
        expectInvalidFile(upload("front.png", "image/png", ArcImages.jpeg(4096)), "content does not match image/png");
        expectInvalidFile(upload("front.pdf", "application/pdf", ArcImages.jpeg(4096)), "must be image/jpeg or image/png");
        expectInvalidFile(upload("front.jpg", "image/jpeg", new byte[0]), "empty file");

        assertThat(storage.puts.get()).isZero();
        assertThat(count("SELECT COUNT(*) FROM documents WHERE user_id = ?", userId)).isZero();
    }

    @Test
    void 십MB를_넘으면_400이고_R2에_올리지_않는다() throws Exception {
        expectInvalidFile(upload("front.jpg", "image/jpeg", ArcImages.jpeg(10 * 1024 * 1024 + 1)), "file too large");

        assertThat(storage.puts.get()).isZero();
    }

    @Test
    void file_파트가_없으면_400() throws Exception {
        mockMvc.perform(multipart(URL).file(new MockMultipartFile("image", "f.jpg", "image/jpeg", ArcImages.jpeg(1024)))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("file"));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(multipart(URL).file(new MockMultipartFile("file", "f.jpg", "image/jpeg", ArcImages.jpeg(1024))))
                .andExpect(status().isUnauthorized());
        assertThat(storage.puts.get()).isZero();
    }

    // ---------- helpers ----------

    private ResultActions upload(String filename, String contentType, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart(URL).file(new MockMultipartFile("file", filename, contentType, bytes))
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private static void expectInvalidFile(ResultActions result, String reason) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("file"))
                .andExpect(jsonPath("$.details.fields[0].reason").value(reason));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return JsonMapper.builder().build().readTree(result.andReturn().getResponse().getContentAsString());
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
