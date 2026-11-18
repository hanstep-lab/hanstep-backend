package com.hanstep.fx.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.fx.SseStreamClient;
import com.hanstep.fx.service.FxAlertStreamService;
import com.hanstep.support.TestUsers;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 목표 환율 도달 스트림의 keep-alive·타임아웃·끊김·종료 정리(#129). 기다리지 않게 ping 200ms, 타임아웃 2초로 띄운다
 * (운영 기본은 25초·30분). 연결이 닫히면 목록(openConnections)에서 빠져야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "hanstep.fx-alert-stream.ping-interval=200ms",
        "hanstep.fx-alert-stream.timeout=2s"})
@ExtendWith(OutputCaptureExtension.class)
class FxAlertStreamTimeoutTest {

    private static final String USER_PREFIX = "test-fx-stream-timeout";
    private static final Duration WAIT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FxAlertStreamService streamService;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private long userId;

    @BeforeEach
    void setUp() {
        cleanUp();
        userId = TestUsers.create(jdbc, USER_PREFIX, "TEST FX STREAM TIMEOUT");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE external_user_key = ?", USER_PREFIX);
    }

    @Test
    void ping_주석이_온다() throws Exception {
        try (SseStreamClient stream = open()) {
            assertThat(SseStreamClient.until(() -> !stream.comments().isEmpty(), WAIT)).isTrue();

            assertThat(stream.comments()).contains(": ping");
            assertThat(stream.events()).extracting(SseStreamClient.Event::name).containsExactly("ready");
        }
    }

    @Test
    void 타임아웃이_지나면_서버가_닫고_목록에서_빠진다(CapturedOutput output) throws Exception {
        try (SseStreamClient stream = open()) {
            stream.await("ready", 1, WAIT);
            assertThat(streamService.openConnections(userId)).isEqualTo(1);

            assertThat(stream.awaitEnd(WAIT)).isTrue();
            assertThat(SseStreamClient.until(() -> streamService.openConnections(userId) == 0, WAIT)).isTrue();
            assertThat(closedReasons(output)).containsExactly("TIMEOUT");
        }
    }

    @Test
    void 앱이_연결을_끊으면_ping_전송_실패로_목록에서_빠진다(CapturedOutput output) throws Exception {
        SseStreamClient stream = open();
        stream.await("ready", 1, WAIT);
        assertThat(streamService.openConnections(userId)).isEqualTo(1);

        stream.close();

        assertThat(SseStreamClient.until(() -> streamService.openConnections(userId) == 0, WAIT)).isTrue();
        // 타임아웃이 아니라 ping 쓰기에서 드러나 빠졌다(쓰기 실패 또는 컨테이너 오류 알림)
        assertThat(closedReasons(output)).hasSize(1).first().isIn("SEND_FAILED", "ERROR");
    }

    @Test
    void 종료가_시작되면_열린_연결을_모두_닫는다(CapturedOutput output) throws Exception {
        // ContextClosedEvent 를 직접 발행하면 다른 빈(RabbitMQ 연결 등)도 닫히므로 받는 메서드를 부른다
        try (SseStreamClient first = open(); SseStreamClient second = open()) {
            first.await("ready", 1, WAIT);
            second.await("ready", 1, WAIT);

            streamService.closeAll();

            assertThat(first.awaitEnd(WAIT)).isTrue();
            assertThat(second.awaitEnd(WAIT)).isTrue();
            assertThat(streamService.openConnections(userId)).isZero();
            assertThat(closedReasons(output)).containsExactly("SHUTDOWN", "SHUTDOWN");
        }
    }

    /** 이 테스트 사용자의 연결이 닫힐 때 남긴 로그의 reason. */
    private List<String> closedReasons(CapturedOutput output) {
        Matcher m = Pattern.compile("fx alert stream closed userId=" + userId + " connectionId=\\d+ reason=(\\w+)")
                .matcher(output.getOut());
        List<String> reasons = new ArrayList<>();
        while (m.find()) {
            reasons.add(m.group(1));
        }
        return reasons;
    }

    private SseStreamClient open() throws Exception {
        return SseStreamClient.open(http, "http://127.0.0.1:" + port + "/api/v1/fx/alerts/stream",
                "Bearer " + jwtTokenProvider.issue(userId));
    }
}
