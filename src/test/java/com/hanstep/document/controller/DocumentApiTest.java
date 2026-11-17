package com.hanstep.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.aijob.dto.JobFailedRequest;
import com.hanstep.aijob.service.AiJobStatusService;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.dto.OcrFieldResult;
import com.hanstep.document.dto.OcrResultRequest;
import com.hanstep.document.service.DocumentInternalService;
import java.math.BigDecimal;
import java.util.List;
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

    @Autowired
    private AiJobStatusService aiJobStatusService;

    @Autowired
    private DocumentInternalService documentInternalService;

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
        String key = (String) doc.get("object_key");
        assertThat(((Number) doc.get("user_id")).longValue()).isEqualTo(userId);
        // arc/{side}/{UUID}.jpg: 사용자·문서 id 를 넣지 않는다(지수 합의 11/16)
        assertThat(key).matches("arc/front/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
                .doesNotContain(String.valueOf(userId) + "/");
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
        assertThat(job.get("idempotency_key")).isEqualTo("ocr:" + documentId);
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
    void OCR이_실패한_문서와_같은_사진을_다시_올리면_실패_문서를_지우고_새_문서로_202() throws Exception {
        byte[] image = ArcImages.jpeg(24 * 1024);
        JsonNode first = json(upload("front.jpg", "image/jpeg", image).andExpect(status().isAccepted()));
        long failedId = first.path("documentId").longValue();
        long failedJobId = first.path("ocrJob").path("jobId").longValue();
        String failedKey = objectKey(failedId);
        aiJobStatusService.markFailed(failedJobId, new JobFailedRequest("OCR_FAILED", "image too blurry", false));

        JsonNode again = json(upload("front.jpg", "image/jpeg", image)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.ocrJob.status").value("PENDING")));
        long newId = again.path("documentId").longValue();

        assertThat(newId).isNotEqualTo(failedId);
        // 실패 문서는 지워지고(사용자당 같은 사진은 1건), 실패 작업 기록은 남는다
        assertThat(count("SELECT COUNT(*) FROM documents WHERE id = ?", failedId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM documents WHERE user_id = ?", userId)).isEqualTo(1);
        Map<String, Object> failedJob = jdbcTemplate.queryForMap("SELECT * FROM ai_jobs WHERE id = ?", failedJobId);
        assertThat(failedJob.get("status")).isEqualTo("FAILED");
        assertThat(((Number) failedJob.get("resource_id")).longValue()).isEqualTo(failedId);
        assertThat(jdbcTemplate.queryForObject("SELECT idempotency_key FROM ai_jobs WHERE job_type = 'OCR' "
                + "AND resource_id = ?", String.class, newId)).isEqualTo("ocr:" + newId);
        // 새 원본은 새 키. 옛 원본은 커밋 뒤에 지운다(이 테스트는 롤백이라 DocumentUploadCommitTest 에서 확인)
        assertThat(objectKey(newId)).isNotEqualTo(failedKey).startsWith("arc/front/");
        assertThat(storage.objects).containsKey(objectKey(newId));
        assertThat(storage.puts.get()).isEqualTo(2);
    }

    @Test
    void OCR_결과가_온_문서와_같은_사진은_기존처럼_같은_문서를_200으로_준다() throws Exception {
        byte[] image = ArcImages.jpeg(24 * 1024);
        long documentId = json(upload("front.jpg", "image/jpeg", image).andExpect(status().isAccepted()))
                .path("documentId").longValue();
        documentInternalService.recordOcrResult(documentId, new OcrResultRequest(documentId, "ppocrv5-ko-mobile-int8",
                842, DocumentSide.FRONT, List.of(
                new OcrFieldResult("nameEn", "MARK ANTHONY REYES", new BigDecimal("0.98"), false),
                new OcrFieldResult("nationality", "PHL", new BigDecimal("0.97"), false),
                new OcrFieldResult("birthDate", "1995-08-15", new BigDecimal("0.97"), false),
                new OcrFieldResult("visaType", "E-9", new BigDecimal("0.95"), false),
                new OcrFieldResult("stayExpiry", null, null, false),
                new OcrFieldResult("registrationNo", "950815-5******", new BigDecimal("0.99"), true))));

        upload("front.jpg", "image/jpeg", image)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"));
        assertThat(storage.puts.get()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ai_jobs WHERE job_type = 'OCR' AND user_id = ?", userId)).isEqualTo(1);
    }

    @Test
    void 다른_사용자가_같은_사진을_올리면_따로_저장한다() throws Exception {
        byte[] image = ArcImages.jpeg(16 * 1024);
        long mine = json(upload("front.jpg", "image/jpeg", image).andExpect(status().isAccepted()))
                .path("documentId").longValue();
        long otherId = TestUsers.create(jdbcTemplate, "test-doc-" + UUID.randomUUID(), "OTHER USER");

        long theirs = json(mockMvc.perform(multipart(URL).file(new MockMultipartFile("front", "f.jpg", "image/jpeg", image))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(otherId)))
                .andExpect(status().isAccepted())).path("documentId").longValue();

        assertThat(theirs).isNotEqualTo(mine);
        // 같은 내용이어도 키는 UUID 라 겹치지 않는다
        assertThat(storage.objects).containsKeys(objectKey(mine), objectKey(theirs));
        assertThat(objectKey(mine)).isNotEqualTo(objectKey(theirs));
    }

    @Test
    void PNG는_png_확장자로_저장한다() throws Exception {
        long documentId = json(upload("front.png", "image/png", ArcImages.png(8 * 1024))
                .andExpect(status().isAccepted())).path("documentId").longValue();

        assertThat(objectKey(documentId)).startsWith("arc/front/").endsWith(".png");
    }

    @Test
    void back_파트가_와도_무시하고_앞면만_저장한다() throws Exception {
        long documentId = json(mockMvc.perform(multipart(URL)
                        .file(new MockMultipartFile("front", "f.jpg", "image/jpeg", ArcImages.jpeg(4096)))
                        .file(new MockMultipartFile("back", "b.jpg", "image/jpeg", ArcImages.jpeg(8192)))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isAccepted())).path("documentId").longValue();

        assertThat(storage.puts.get()).isEqualTo(1);
        assertThat(objectKey(documentId)).startsWith("arc/front/");
        assertThat(count("SELECT COUNT(*) FROM documents WHERE user_id = ?", userId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM documents WHERE user_id = ? AND side = 'BACK'", userId)).isZero();
    }

    @Test
    void back_만_오면_front_없음_400이고_아무것도_저장하지_않는다() throws Exception {
        mockMvc.perform(multipart(URL).file(new MockMultipartFile("back", "b.jpg", "image/jpeg", ArcImages.jpeg(4096)))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("front"));

        assertThat(storage.puts.get()).isZero();
        assertThat(count("SELECT COUNT(*) FROM documents WHERE user_id = ?", userId)).isZero();
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
    void front_파트가_없으면_400() throws Exception {
        mockMvc.perform(multipart(URL).file(new MockMultipartFile("file", "f.jpg", "image/jpeg", ArcImages.jpeg(1024)))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("front"))
                .andExpect(jsonPath("$.details.fields[0].reason").value("required part"));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(multipart(URL).file(new MockMultipartFile("front", "f.jpg", "image/jpeg", ArcImages.jpeg(1024))))
                .andExpect(status().isUnauthorized());
        assertThat(storage.puts.get()).isZero();
    }

    // ---------- helpers ----------

    private ResultActions upload(String filename, String contentType, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart(URL).file(new MockMultipartFile("front", filename, contentType, bytes))
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private String objectKey(long documentId) {
        return jdbcTemplate.queryForObject("SELECT object_key FROM documents WHERE id = ?", String.class, documentId);
    }

    private static void expectInvalidFile(ResultActions result, String reason) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("front"))
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
