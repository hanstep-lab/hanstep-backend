package com.hanstep.document.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.document.ArcImages;
import com.hanstep.document.FakeArcStorageConfig;
import com.hanstep.document.FakeArcStorageConfig.FakeArcImageStorage;
import com.hanstep.support.TestUsers;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 업로드 크기 제한을 실제 서버(Tomcat)로 확인한다. MockMvc 는 multipart 크기 제한·Tomcat 본문 처리를 거치지 않아 여기서 본다.
 * 10MB 를 넘는 본문도 연결을 끊지 않고 400 VALIDATION_ERROR 를 받아야 한다(server.tomcat.max-swallow-size).
 * 실제 DB(사용자만 만들고 지움), R2 대신 메모리 저장소. 요청이 거절되므로 OCR 작업은 만들어지지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeArcStorageConfig.class)
class DocumentUploadSizeLimitTest {

    private static final String BOUNDARY = "sizelimit7d2b";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private FakeArcImageStorage storage;

    private final String userKey = "test-doc-size-" + UUID.randomUUID();
    private long userId;

    @BeforeEach
    void setUp() {
        storage.reset();
        userId = TestUsers.create(jdbcTemplate, userKey, "MARK ANTHONY REYES");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE external_user_key = ?", userKey);
    }

    @Test
    void 십일MB를_올리면_연결을_끊지_않고_400_VALIDATION_ERROR() throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/documents"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + jwtTokenProvider.issue(userId))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(ArcImages.jpeg(11 * 1024 * 1024))))
                        .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = JsonMapper.builder().build().readTree(response.body());
        assertThat(body.path("code").stringValue()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("details").path("fields").path(0).path("field").stringValue()).isEqualTo("front");
        assertThat(storage.puts.get()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM documents WHERE user_id = ?", Integer.class,
                userId)).isZero();
    }

    private static byte[] multipart(byte[] jpeg) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(jpeg.length + 512);
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"front\"; filename=\"front.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(jpeg);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }
}
