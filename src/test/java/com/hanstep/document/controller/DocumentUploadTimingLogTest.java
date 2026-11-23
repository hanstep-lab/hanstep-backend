package com.hanstep.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.document.ArcImages;
import com.hanstep.document.FakeArcStorageConfig;
import com.hanstep.document.FakeArcStorageConfig.FakeArcImageStorage;
import com.hanstep.support.TestUsers;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * 등록증 업로드 처리 시간 한 줄(#167). DocumentApiTest 와 같은 구성(실제 DB, 테스트마다 롤백, R2 대신 메모리 저장소).
 * 롤백하는 테스트라 커밋·발행이 없어 commitPublishMs 는 0 에 가깝다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FakeArcStorageConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class DocumentUploadTimingLogTest {

    private static final String FILENAME = "my-arc-photo-name.jpg";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private FakeArcImageStorage storage;

    @Autowired
    private EntityManager entityManager;

    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        storage.reset();
        userId = TestUsers.create(jdbcTemplate, "test-doc-timing-" + UUID.randomUUID(), "MARK ANTHONY REYES");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    @Test
    void 새로_올리면_CREATED_한_줄에_bytes_r2PutMs_dbMs_totalMs가_있다(CapturedOutput output) throws Exception {
        byte[] image = ArcImages.jpeg(48 * 1024);

        long documentId = documentId(upload(image).andExpect(status().isAccepted()));

        assertThat(uploadLines(output)).singleElement().asString().matches(
                "document upload: documentId=" + documentId + " side=FRONT result=CREATED bytes=" + image.length
                        + " r2PutMs=\\d+ dbMs=\\d+ commitPublishMs=\\d+ totalMs=\\d+");
        assertThat(storage.puts.get()).isEqualTo(1);
    }

    @Test
    void 같은_사진을_다시_올리면_EXISTING이고_r2PutMs가_없다(CapturedOutput output) throws Exception {
        byte[] image = ArcImages.jpeg(40 * 1024);
        long first = documentId(upload(image).andExpect(status().isAccepted()));

        long again = documentId(upload(image).andExpect(status().isOk()));

        assertThat(again).isEqualTo(first);
        List<String> lines = uploadLines(output);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).matches(
                "document upload: documentId=" + first + " side=FRONT result=EXISTING bytes=" + image.length
                        + " totalMs=\\d+");
        assertThat(lines.get(1)).doesNotContain("r2PutMs");
        assertThat(storage.puts.get()).isEqualTo(1);
    }

    @Test
    void 원본이_만료된_문서와_같은_사진을_다시_올리면_REPLACED이고_r2PutMs가_있다(CapturedOutput output) throws Exception {
        byte[] image = ArcImages.jpeg(36 * 1024);
        long expiredId = documentId(upload(image).andExpect(status().isAccepted()));
        expireOriginal(expiredId);

        long newId = documentId(upload(image).andExpect(status().isAccepted()));

        assertThat(newId).isNotEqualTo(expiredId);
        List<String> lines = uploadLines(output);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).matches(
                "document upload: documentId=" + newId + " side=FRONT result=REPLACED bytes=" + image.length
                        + " r2PutMs=\\d+ dbMs=\\d+ commitPublishMs=\\d+ totalMs=\\d+");
        assertThat(storage.puts.get()).isEqualTo(2);
    }

    @Test
    void 로그에는_object_key_sha256_파일명_userId가_없다(CapturedOutput output) throws Exception {
        byte[] image = ArcImages.jpeg(32 * 1024);
        long documentId = documentId(upload(image).andExpect(status().isAccepted()));
        upload(image).andExpect(status().isOk());

        String objectKey = jdbcTemplate.queryForObject("SELECT object_key FROM documents WHERE id = ?", String.class,
                documentId);
        String sha256 = DocumentApiTest.sha256(image);
        assertThat(objectKey).isNotBlank();
        // 이 요청들이 남긴 로그 전체에서 본다(처리 시간 줄뿐 아니라 다른 줄에도 원본 위치·해시가 새지 않게)
        assertThat(output.getOut()).contains("document upload:")
                .doesNotContain(objectKey, sha256, FILENAME, "userId=" + userId);
    }

    // ---------- helpers ----------

    private ResultActions upload(byte[] bytes) throws Exception {
        return mockMvc.perform(multipart(DocumentApiTest.URL)
                .file(new MockMultipartFile("front", FILENAME, "image/jpeg", bytes))
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    // DocumentApiTest.expireOriginal 과 같다: 보관 기한이 지나 원본을 지운 상태(#162·#165)
    private void expireOriginal(long documentId) {
        entityManager.flush();
        entityManager.clear();
        jdbcTemplate.update("UPDATE documents SET object_key = NULL, original_deleted_at = CURRENT_TIMESTAMP(3) "
                + "WHERE id = ?", documentId);
    }

    private static long documentId(ResultActions result) throws Exception {
        return JsonMapper.builder().build().readTree(result.andReturn().getResponse().getContentAsString())
                .path("documentId").longValue();
    }

    // 이 테스트가 남긴 처리 시간 줄의 "document upload:" 부터 끝까지(앞의 시각·레벨·requestId 는 뺀다)
    private static List<String> uploadLines(CapturedOutput output) {
        return output.getOut().lines()
                .filter(line -> line.contains("document upload:"))
                .map(line -> line.substring(line.indexOf("document upload:")).strip())
                .toList();
    }
}
