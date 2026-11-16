package com.hanstep.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.aijob.RabbitTestBroker;
import com.hanstep.aijob.messaging.AiJobRoute;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.document.ArcImages;
import com.hanstep.document.FakeArcStorageConfig;
import com.hanstep.document.FakeArcStorageConfig.FakeArcImageStorage;
import com.hanstep.support.TestUsers;
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
 * 업로드가 실제로 커밋될 때: OCR 작업 발행, R2 실패 시 롤백, R2 에 올린 뒤 롤백되면 객체 정리.
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
        assertThat(storage.objects).containsKey("arc/" + userId + "/" + documentId + "/front.jpg");
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

    private ResultActions upload(byte[] image) throws Exception {
        return mockMvc.perform(multipart(DocumentApiTest.URL)
                .file(new MockMultipartFile("file", "front.jpg", "image/jpeg", image))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(userId)));
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class, userId);
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return JsonMapper.builder().build().readTree(result.andReturn().getResponse().getContentAsString());
    }
}
