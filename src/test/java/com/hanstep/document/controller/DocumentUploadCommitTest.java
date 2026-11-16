package com.hanstep.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.aijob.RabbitTestBroker;
import com.hanstep.aijob.messaging.AiJobRoute;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.document.ArcImages;
import com.hanstep.document.FakeArcStorageConfig;
import com.hanstep.document.FakeArcStorageConfig.FakeArcImageStorage;
import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.dto.OcrFieldResult;
import com.hanstep.document.dto.OcrResultRequest;
import com.hanstep.document.service.DocumentInternalService;
import com.hanstep.support.TestUsers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제로 커밋될 때: 업로드의 OCR 작업 발행, 발행 실패 뒤 다시 시도(U2), R2 실패 시 롤백, R2 에 올린 뒤 롤백되면 객체 정리,
 * 확정 중 R2 삭제 실패 시 롤백.
 * 실제 MySQL(커밋, 끝나고 사용자 삭제 → documents·ai_jobs CASCADE) + Testcontainers RabbitMQ + 메모리 저장소.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeArcStorageConfig.class)
class DocumentUploadCommitTest {

    private static final String OCR_QUEUE = AiJobRoute.OCR.routingKey();

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        RabbitTestBroker.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private FakeArcImageStorage storage;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private DocumentInternalService documentInternalService;

    private final String userKey = "test-doc-commit-" + UUID.randomUUID();
    private long userId;

    @BeforeEach
    void setUp() {
        storage.reset();
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(OCR_QUEUE, false);
        userId = TestUsers.create(jdbcTemplate, userKey, "MARK ANTHONY REYES");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE external_user_key = ?", userKey);
    }

    @Test
    void 커밋되면_OCR_작업을_ocr_request로_보내고_응답에_PUBLISHED가_보인다() throws Exception {
        JsonNode body = json(upload(ArcImages.jpeg(20_000)).andExpect(status().isAccepted()));
        long documentId = body.path("documentId").longValue();

        assertThat(body.path("ocrJob").path("status").stringValue()).isEqualTo("QUEUED");
        assertThat(body.path("ocrJob").path("publishStatus").stringValue()).isEqualTo("PUBLISHED");
        Message message = rabbitTemplate.receive(OCR_QUEUE, 5_000);
        assertThat(message).isNotNull();
        JsonNode payload = JsonMapper.builder().build().readTree(message.getBody());
        assertThat(payload.path("jobType").stringValue()).isEqualTo("OCR");
        assertThat(payload.path("resourceId").longValue()).isEqualTo(documentId);
        // 메시지에 사용자·원본 위치 같은 정보는 없다
        assertThat(new String(message.getBody())).doesNotContain("arc/", "REYES");
        assertThat(storage.objects).containsKey(objectKey(documentId));
        assertThat(storage.deletes.get()).isZero();
    }

    @Test
    void R2_업로드가_실패하면_문서와_작업이_남지_않는다() throws Exception {
        storage.failPut = true;

        upload(ArcImages.jpeg(20_000)).andExpect(status().isInternalServerError());

        assertThat(count("SELECT COUNT(*) FROM documents WHERE user_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM ai_jobs WHERE user_id = ?")).isZero();
        assertThat(storage.objects).isEmpty();
        assertThat(rabbitTemplate.receive(OCR_QUEUE, 500)).isNull();
    }

    @Test
    void R2에_써진_뒤_실패해_롤백되면_올린_객체를_지운다() throws Exception {
        storage.failAfterStore = true;   // 객체는 써졌지만 응답 전에 끊긴 경우

        upload(ArcImages.jpeg(20_000)).andExpect(status().isInternalServerError());

        assertThat(storage.puts.get()).isEqualTo(1);
        assertThat(storage.deletes.get()).isEqualTo(1);
        assertThat(storage.objects).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM documents WHERE user_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM ai_jobs WHERE user_id = ?")).isZero();
    }

    @Test
    void 발행에_실패하면_업로드는_202이고_다시_시도로_같은_작업을_보낸다() throws Exception {
        rabbitAdmin.deleteQueue(OCR_QUEUE);   // 라우팅 안 됨 → PUBLISH_FAILED
        JsonNode uploaded;
        try {
            uploaded = json(upload(ArcImages.jpeg(20_000)).andExpect(status().isAccepted()));
        } finally {
            rabbitAdmin.initialize();
        }
        long documentId = uploaded.path("documentId").longValue();
        long jobId = uploaded.path("ocrJob").path("jobId").longValue();
        assertThat(uploaded.path("ocrJob").path("status").stringValue()).isEqualTo("PENDING");
        assertThat(uploaded.path("ocrJob").path("publishStatus").stringValue()).isEqualTo("PUBLISH_FAILED");
        assertThat(uploaded.path("ocrJob").path("retryable").booleanValue()).isTrue();

        JsonNode retried = json(retry(documentId, userId).andExpect(status().isAccepted()));

        assertThat(retried.path("ocrJob").path("jobId").longValue()).isEqualTo(jobId);
        assertThat(retried.path("ocrJob").path("status").stringValue()).isEqualTo("QUEUED");
        assertThat(retried.path("ocrJob").path("publishStatus").stringValue()).isEqualTo("PUBLISHED");
        assertThat(retried.path("ocrJob").path("retryable").booleanValue()).isFalse();
        Message message = rabbitTemplate.receive(OCR_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(JsonMapper.builder().build().readTree(message.getBody()).path("jobId").longValue()).isEqualTo(jobId);
        assertThat(count("SELECT COUNT(*) FROM ai_jobs WHERE user_id = ?")).isEqualTo(1);

        // 이미 큐에 들어갔으면 409, 남의 문서는 404
        retry(documentId, userId).andExpect(status().isConflict());
        long otherId = TestUsers.create(jdbcTemplate, userKey + "-other", "OTHER USER");
        try {
            retry(documentId, otherId).andExpect(status().isNotFound());
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", otherId);
        }
    }

    @Test
    void 확정_중_R2_원본_삭제가_실패하면_롤백되어_확정_전_상태로_남는다() throws Exception {
        long documentId = json(upload(ArcImages.jpeg(20_000)).andExpect(status().isAccepted()))
                .path("documentId").longValue();
        documentInternalService.recordOcrResult(documentId, new OcrResultRequest(documentId, "ppocrv5-ko-mobile-int8",
                842, DocumentSide.FRONT, List.of(
                new OcrFieldResult("nameEn", "MARK ANTHONY REYES", new BigDecimal("0.98"), false),
                new OcrFieldResult("nationality", "PHL", new BigDecimal("0.97"), false),
                new OcrFieldResult("birthDate", "1995-08-15", new BigDecimal("0.97"), false),
                new OcrFieldResult("visaType", "E-9", new BigDecimal("0.95"), false),
                new OcrFieldResult("stayExpiry", "2027-03-01", new BigDecimal("0.90"), false),
                new OcrFieldResult("registrationNo", "950815-5******", new BigDecimal("0.99"), true))));
        storage.failDelete = true;

        mockMvc.perform(post(DocumentApiTest.URL + "/{id}/confirm", documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(userId)))
                .andExpect(status().isInternalServerError());

        Map<String, Object> doc = jdbcTemplate.queryForMap("SELECT * FROM documents WHERE id = ?", documentId);
        assertThat(doc.get("status")).isEqualTo("PENDING_CONFIRMATION");
        assertThat((String) doc.get("object_key")).startsWith("arc/front/");
        assertThat(storage.objects).containsKey((String) doc.get("object_key"));
        assertThat(count("SELECT COUNT(*) FROM visa_profiles WHERE user_id = ?")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_fields WHERE document_id = ? "
                + "AND extracted_value IS NOT NULL", Long.class, documentId)).isEqualTo(6);
        assertThat(storage.objects).hasSize(1);
    }

    private ResultActions retry(long documentId, long asUser) throws Exception {
        return mockMvc.perform(post(DocumentApiTest.URL + "/{id}/ocr-retry", documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(asUser)));
    }

    private ResultActions upload(byte[] image) throws Exception {
        return mockMvc.perform(multipart(DocumentApiTest.URL)
                .file(new MockMultipartFile("front", "front.jpg", "image/jpeg", image))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(userId)));
    }

    private String objectKey(long documentId) {
        return jdbcTemplate.queryForObject("SELECT object_key FROM documents WHERE id = ?", String.class, documentId);
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class, userId);
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return JsonMapper.builder().build().readTree(result.andReturn().getResponse().getContentAsString());
    }
}
