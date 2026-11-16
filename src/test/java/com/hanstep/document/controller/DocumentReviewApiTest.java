package com.hanstep.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import java.math.BigDecimal;
import java.util.List;
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
 * 등록증 OCR 결과 확인 흐름(DOC-02 조회). 업로드(DOC-01) → worker 결과 보고(DocumentInternalService, 실제 경로) → 조회.
 * 실제 DB, 테스트마다 롤백. R2 대신 메모리 저장소. OCR 값은 가상 인물(Reyes) 합성 데이터.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FakeArcStorageConfig.class)
class DocumentReviewApiTest {

    private static final String URL = DocumentApiTest.URL + "/{id}";

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

    long userId;
    String bearer;

    @BeforeEach
    void setUp() {
        storage.reset();
        userId = TestUsers.create(jdbcTemplate, "test-doc-review-" + UUID.randomUUID(), "MARK ANTHONY REYES");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    // ---------- DOC-02 조회 ----------

    @Test
    void OCR_결과_전에는_UPLOADED이고_fields가_비어_있다() throws Exception {
        long id = upload();

        mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(id))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.ocrJob.status").value("PENDING"))
                .andExpect(jsonPath("$.fields").isEmpty());
    }

    @Test
    void OCR_결과가_오면_6개_항목을_화면_순서로_주고_신뢰도_낮거나_없는_항목은_확인_필요() throws Exception {
        long id = upload();
        recordOcr(id, new BigDecimal("0.7999"));

        JsonNode body = json(mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.ocrJob.status").value("COMPLETED")));

        JsonNode fields = body.path("fields");
        assertThat(fields.valueStream().map(f -> f.path("fieldKey").stringValue()))
                .containsExactly("nameEn", "nationality", "birthDate", "visaType", "stayExpiry", "registrationNo");
        assertThat(field(fields, "nameEn").path("value").stringValue()).isEqualTo("MARK ANTHONY REYES");
        assertThat(field(fields, "nameEn").path("needsReview").booleanValue()).isFalse();
        assertThat(field(fields, "nameEn").path("status").stringValue()).isEqualTo("EXTRACTED");
        // 0.8 미만
        assertThat(field(fields, "visaType").path("confidence").decimalValue()).isEqualByComparingTo("0.7999");
        assertThat(field(fields, "visaType").path("needsReview").booleanValue()).isTrue();
        // 앞면에 없어 못 읽음
        assertThat(field(fields, "stayExpiry").path("value").isNull()).isTrue();
        assertThat(field(fields, "stayExpiry").path("status").stringValue()).isEqualTo("MISSING");
        assertThat(field(fields, "stayExpiry").path("needsReview").booleanValue()).isTrue();
        // 등록번호는 마스킹 값만
        assertThat(field(fields, "registrationNo").path("value").stringValue()).isEqualTo("950815-5******");
        assertThat(field(fields, "registrationNo").path("isMasked").booleanValue()).isTrue();
    }

    @Test
    void 다른_사용자의_문서나_없는_문서는_404() throws Exception {
        long id = upload();
        long otherId = TestUsers.create(jdbcTemplate, "test-doc-review-" + UUID.randomUUID(), "OTHER USER");

        mockMvc.perform(get(URL, id).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(otherId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get(URL, Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    long upload() throws Exception {
        return json(mockMvc.perform(multipart(DocumentApiTest.URL)
                        .file(new MockMultipartFile("file", "front.jpg", "image/jpeg", ArcImages.jpeg(8 * 1024)))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isAccepted())).path("documentId").longValue();
    }

    /** worker 의 OCR 결과 보고(합성 데이터). visaType 신뢰도만 바꿔 넣는다. */
    void recordOcr(long documentId, BigDecimal visaTypeConfidence) {
        documentInternalService.recordOcrResult(documentId, new OcrResultRequest(documentId, "ppocrv5-ko-mobile-int8",
                842, DocumentSide.FRONT, List.of(
                new OcrFieldResult("nameEn", "MARK ANTHONY REYES", new BigDecimal("0.9812"), false),
                new OcrFieldResult("nationality", "PHL", new BigDecimal("0.9655"), false),
                new OcrFieldResult("birthDate", "1995-08-15", new BigDecimal("0.9734"), false),
                new OcrFieldResult("visaType", "E-9", visaTypeConfidence, false),
                new OcrFieldResult("stayExpiry", null, null, false),
                new OcrFieldResult("registrationNo", "950815-5******", new BigDecimal("0.9907"), true))));
    }

    static JsonNode field(JsonNode fields, String key) {
        return fields.valueStream().filter(f -> f.path("fieldKey").stringValue().equals(key)).findFirst().orElseThrow();
    }

    static JsonNode json(ResultActions result) throws Exception {
        return JsonMapper.builder().build().readTree(result.andReturn().getResponse().getContentAsString());
    }
}
