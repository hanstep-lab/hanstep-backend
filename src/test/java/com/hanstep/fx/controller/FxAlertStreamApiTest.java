package com.hanstep.fx.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.fx.SseStreamClient;
import com.hanstep.fx.SseStreamClient.Event;
import com.hanstep.fx.domain.FxSource;
import com.hanstep.fx.service.ExchangeRatesSavedEvent;
import com.hanstep.fx.service.ExchangeRatesSavedEvent.SavedRate;
import com.hanstep.fx.service.FxAlertStreamService;
import com.hanstep.fx.service.FxAlertTriggerService;
import com.hanstep.support.TestUsers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 목표 환율 도달 스트림(GET /api/v1/fx/alerts/stream, #129). 실제 포트로 띄운 Spring 에 앱 역할 HTTP 클라이언트로 연결한다
 * (연결 끊김·비동기 디스패치를 보려고 MockMvc 대신 실제 HTTP). 서버 스레드가 읽어야 해서 @Transactional 없이 커밋하고,
 * 넣은 사용자(알림은 함께 지워짐)·환율은 끝나고 지운다. 환율은 시험용 코드 XTS, 기준일 2099-02-01.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FxAlertStreamApiTest {

    private static final String USER_PREFIX = "test-fx-stream";
    private static final Duration WAIT = Duration.ofSeconds(5);
    /** 오지 않아야 하는 이벤트를 기다려 보는 시간. */
    private static final Duration QUIET = Duration.ofMillis(800);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final AtomicInteger RATE_SECONDS = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FxAlertTriggerService triggerService;

    @Autowired
    private FxAlertStreamService streamService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final List<SseStreamClient> clients = new ArrayList<>();
    private long userId;
    private long otherId;

    @BeforeEach
    void setUp() {
        cleanUp();
        userId = TestUsers.create(jdbc, USER_PREFIX + "-a", "TEST FX STREAM A");
        otherId = TestUsers.create(jdbc, USER_PREFIX + "-b", "TEST FX STREAM B");
    }

    @AfterEach
    void cleanUp() {
        for (SseStreamClient client : clients) {
            try {
                client.close();
            } catch (Exception ignored) {
                // 이미 닫힘
            }
        }
        clients.clear();
        jdbc.update("DELETE FROM users WHERE external_user_key LIKE ?", USER_PREFIX + "%"); // fx_alerts 도 지워진다
        jdbc.update("DELETE FROM exchange_rates WHERE currency = 'XTS' AND rate_date = '2099-02-01'");
    }

    @Test
    void 연결하면_ready_뒤에_아직_알리지_않은_도달을_최신순으로_보충하고_항목은_notifications와_같다() throws Exception {
        long rateId = rate("1300.0000", "1313.0000");
        long older = triggered(userId, "SEND", "LTE", "1400", rateId, "2099-02-01 03:00:00");
        long newer = triggered(userId, "BASE", "LTE", "1350", rateId, "2099-02-01 04:00:00");
        long notified = triggered(userId, "SEND", "GTE", "1200", rateId, "2099-02-01 05:00:00");
        jdbc.update("UPDATE fx_alerts SET notified_at = NOW(3) WHERE id = ?", notified);
        long cancelled = triggered(userId, "BASE", "GTE", "1250", rateId, "2099-02-01 06:00:00");
        jdbc.update("UPDATE fx_alerts SET status = 'CANCELLED' WHERE id = ?", cancelled);
        triggered(otherId, "SEND", "LTE", "1400", rateId, "2099-02-01 07:00:00");

        SseStreamClient stream = open(userId);
        List<Event> backfill = stream.await("fx-alert-triggered", 2, WAIT);
        Thread.sleep(QUIET.toMillis());

        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.contentType()).startsWith("text/event-stream");
        assertThat(stream.events().getFirst().name()).isEqualTo("ready");
        assertThat(MAPPER.readTree(stream.events().getFirst().data()).path("pingIntervalSeconds").asLong()).isEqualTo(25);
        assertThat(stream.events()).hasSize(3);
        assertThat(backfill).extracting(Event::id).containsExactly(String.valueOf(newer), String.valueOf(older));

        JsonNode items = MAPPER.readTree(get("/api/v1/fx/alerts/notifications", userId)).path("notifications");
        assertThat(items).hasSize(2);
        for (int i = 0; i < 2; i++) {
            JsonNode data = MAPPER.readTree(backfill.get(i).data());
            assertThat(data).isEqualTo(items.get(i));
            assertThat(data.propertyNames()).containsExactly("alertId", "currency", "rateType", "comparator",
                    "targetRate", "unitAmount", "triggeredRate", "triggeredAt", "rateDate", "source");
        }
        // 보낸 것만으로는 알렸다고 보지 않는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fx_alerts WHERE user_id = ? AND status = 'TRIGGERED' "
                + "AND notified_at IS NULL", Integer.class, userId)).isEqualTo(2);
    }

    @Test
    void notified_뒤에_다시_연결하면_그_도달은_다시_오지_않는다() throws Exception {
        long rateId = rate("1300.0000", "1313.0000");
        long alertId = triggered(userId, "SEND", "LTE", "1400", rateId, "2099-02-01 03:00:00");
        SseStreamClient first = open(userId);
        assertThat(first.await("fx-alert-triggered", 1, WAIT)).hasSize(1);
        first.close();

        // 알리기 전 다시 연결하면 또 온다(앱이 alertId 로 한 번만 알림)
        SseStreamClient again = open(userId);
        assertThat(again.await("fx-alert-triggered", 1, WAIT)).extracting(Event::id)
                .containsExactly(String.valueOf(alertId));
        again.close();

        assertThat(post("/api/v1/fx/alerts/" + alertId + "/notified", userId)).isEqualTo(204);
        SseStreamClient afterNotified = open(userId);
        afterNotified.await("ready", 1, WAIT);
        Thread.sleep(QUIET.toMillis());
        assertThat(afterNotified.events()).extracting(Event::name).containsExactly("ready");
    }

    @Test
    void 판정이_커밋되면_열린_연결로_한_번_오고_다른_사용자에게는_가지_않는다() throws Exception {
        SseStreamClient mine = open(userId);
        SseStreamClient mySecondDevice = open(userId);
        SseStreamClient others = open(otherId);
        mine.await("ready", 1, WAIT);
        mySecondDevice.await("ready", 1, WAIT);
        others.await("ready", 1, WAIT);
        long alertId = active(userId, "SEND", "LTE", "1313.5");
        active(otherId, "SEND", "LTE", "1000"); // 닿지 않음
        long rateId = rate("1300.0000", "1313.0000");

        // 수집기와 같은 경로. 판정에 트랜잭션이 없어 UPDATE 가 바로 커밋된다
        triggerService.onRatesSaved(savedEvent(rateId));
        List<Event> received = mine.await("fx-alert-triggered", 1, WAIT);
        triggerService.onRatesSaved(savedEvent(rateId)); // 이미 TRIGGERED 라 다시 보내지 않는다
        Thread.sleep(QUIET.toMillis());

        assertThat(received).hasSize(1);
        assertThat(mine.events("fx-alert-triggered")).hasSize(1);
        assertThat(received.getFirst().id()).isEqualTo(String.valueOf(alertId));
        JsonNode data = MAPPER.readTree(received.getFirst().data());
        assertThat(data.path("alertId").asLong()).isEqualTo(alertId);
        assertThat(data.path("triggeredRate").decimalValue()).isEqualByComparingTo("1313");
        assertThat(data.path("source").asString()).isEqualTo("KOREAEXIM");
        assertThat(mySecondDevice.events("fx-alert-triggered")).hasSize(1);
        assertThat(others.events()).extracting(Event::name).containsExactly("ready");
        assertThat(jdbc.queryForObject("SELECT notified_at FROM fx_alerts WHERE id = ?", String.class, alertId))
                .isNull();
    }

    @Test
    void 판정_트랜잭션이_롤백되면_보내지_않고_커밋된_뒤에만_보낸다() throws Exception {
        SseStreamClient stream = open(userId);
        stream.await("ready", 1, WAIT);
        long alertId = active(userId, "SEND", "LTE", "1313.5");
        long rateId = rate("1300.0000", "1313.0000");

        transactionTemplate.executeWithoutResult(tx -> {
            triggerService.onRatesSaved(savedEvent(rateId));
            tx.setRollbackOnly();
        });
        Thread.sleep(QUIET.toMillis());
        assertThat(stream.events("fx-alert-triggered")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM fx_alerts WHERE id = ?", String.class, alertId))
                .isEqualTo("ACTIVE");

        List<Integer> beforeCommit = new ArrayList<>();
        transactionTemplate.executeWithoutResult(tx -> {
            triggerService.onRatesSaved(savedEvent(rateId));
            sleep(QUIET);
            beforeCommit.add(stream.events("fx-alert-triggered").size());
        });
        assertThat(beforeCommit).containsExactly(0);
        assertThat(stream.await("fx-alert-triggered", 1, WAIT)).extracting(Event::id)
                .containsExactly(String.valueOf(alertId));
    }

    @Test
    void 연결이_없으면_판정은_그대로_되고_다음_연결_때_보충된다() throws Exception {
        long alertId = active(userId, "SEND", "LTE", "1313.5");
        triggerService.onRatesSaved(savedEvent(rate("1300.0000", "1313.0000")));
        assertThat(jdbc.queryForObject("SELECT status FROM fx_alerts WHERE id = ?", String.class, alertId))
                .isEqualTo("TRIGGERED");

        SseStreamClient stream = open(userId);

        assertThat(stream.await("fx-alert-triggered", 1, WAIT)).extracting(Event::id)
                .containsExactly(String.valueOf(alertId));
    }

    @Test
    void 사용자당_3개를_넘으면_가장_오래된_연결을_닫는다() throws Exception {
        List<SseStreamClient> streams = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            SseStreamClient stream = open(userId);
            stream.await("ready", 1, WAIT);
            streams.add(stream);
        }

        assertThat(streams.getFirst().awaitEnd(WAIT)).isTrue();
        assertThat(streamService.openConnections(userId)).isEqualTo(3);
        assertThat(streams.subList(1, 4)).noneMatch(SseStreamClient::ended);
    }

    @Test
    void 토큰이_없거나_틀리면_401_JSON() throws Exception {
        for (String bearer : new String[] {null, "Bearer not-a-jwt"}) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url("/api/v1/fx/alerts/stream")))
                    .header("Accept", "text/event-stream").timeout(WAIT).GET();
            if (bearer != null) {
                request.header("Authorization", bearer);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            assertThat(MAPPER.readTree(response.body()).path("code").asString()).isEqualTo("UNAUTHORIZED");
        }
        assertThat(streamService.openConnections()).isZero();
    }

    private SseStreamClient open(long owner) throws Exception {
        SseStreamClient client = SseStreamClient.open(http, url("/api/v1/fx/alerts/stream"), bearer(owner));
        clients.add(client);
        return client;
    }

    private String bearer(long owner) {
        return "Bearer " + jwtTokenProvider.issue(owner);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String get(String path, long owner) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).header("Authorization", bearer(owner))
                .timeout(WAIT).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private int post(String path, long owner) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).header("Authorization", bearer(owner))
                .timeout(WAIT).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ExchangeRatesSavedEvent savedEvent(long rateId) {
        SavedRate saved = jdbc.queryForObject("SELECT currency, rate_date, rate_at FROM exchange_rates WHERE id = ?",
                (rs, i) -> new SavedRate(rateId, rs.getString("currency"), rs.getObject("rate_date", LocalDate.class),
                        rs.getObject("rate_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), rateId);
        return new ExchangeRatesSavedEvent(FxSource.KOREAEXIM, false, List.of(saved));
    }

    /** 1분 전에 만든 ACTIVE 알림(지금 저장하는 환율 행이 알림 뒤라 판정 대상). */
    private long active(long owner, String rateType, String comparator, String target) {
        return insert("INSERT INTO fx_alerts (user_id, currency, rate_type, comparator, target_rate, created_at) "
                + "VALUES (?, 'XTS', ?, ?, ?, NOW(3) - INTERVAL 1 MINUTE)", owner, rateType, comparator, target);
    }

    /** 이미 도달한 알림. 시각은 UTC. */
    private long triggered(long owner, String rateType, String comparator, String target, long rateId,
                           String triggeredAtUtc) {
        return insert("INSERT INTO fx_alerts (user_id, currency, rate_type, comparator, target_rate, status, "
                        + "triggered_rate_id, triggered_at, created_at) VALUES (?, 'XTS', ?, ?, ?, 'TRIGGERED', ?, ?, "
                        + "'2099-02-01 00:00:00')", owner, rateType, comparator, target, rateId, triggeredAtUtc);
    }

    /** 수출입은행 형식 환율 1행(지금 처음 저장). rate_at 은 겹치지 않게 1초씩 늘린다. */
    private long rate(String base, String send) {
        return insert("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate, "
                        + "receive_rate, source, is_mock, fetched_at) VALUES ('XTS', 1, '2099-02-01', "
                        + "'2099-01-31 15:00:00' + INTERVAL ? SECOND, ?, ?, ?, 'KOREAEXIM', FALSE, NOW(3))",
                RATE_SECONDS.incrementAndGet(), base, send, base);
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, key);
        return key.getKey().longValue();
    }
}
