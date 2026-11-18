package com.hanstep.fx.service;

import com.hanstep.fx.config.FxAlertStreamProperties;
import com.hanstep.fx.dto.FxAlertNotification;
import com.hanstep.fx.dto.FxAlertStreamReady;
import com.hanstep.fx.repository.FxAlertRepository;
import com.hanstep.fx.repository.FxAlertRepository.NotificationRow;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * 목표 환율 도달 스트림(GET /api/v1/fx/alerts/stream, #129). 앱이 켜져 있는 동안 연결을 열어 두면 도달을 이벤트로 보낸다.
 * 원격 푸시는 없고 앱이 꺼져 있으면 오지 않는다(다음 연결 때 보충).
 * - 연결 목록은 메모리에 둔다(단일 인스턴스, 중계 없음). 재배포하면 모두 끊기고 앱이 다시 연결한다.
 *   한 사용자 연결은 maxConnectionsPerUser(3)개까지, 넘으면 가장 오래된 연결을 닫는다(끊긴 걸 모르는 연결이 쌓이지 않게)
 * - 연결하면 event: ready, 이어서 보충: 아직 알리지 않은 도달(TRIGGERED && notified_at IS NULL)을 notifications 와 같은
 *   항목·순서로 event: fx-alert-triggered, id: alertId 로 보낸다. 등록한 뒤에 보충을 읽어 그사이 도달을 놓치지 않는다
 * - 보낸 것만으로 알렸다고 보지 않는다. 앱이 POST notified 를 보내야 notified_at 이 채워지고 보충에서 빠진다.
 *   그래서 같은 alertId 가 두 번 올 수 있다(연결 직후 판정이 겹치거나 notified 전에 다시 연결). 앱은 alertId 로 한 번만 알린다
 * - 도달 판정이 커밋된 뒤(FxAlertTriggeredEvent) 그 사용자의 열린 연결에 같은 이벤트를 보낸다. 연결이 없으면 아무것도 안 함
 * - pingInterval 마다 ": ping" 주석. 보충·도달·ping 전송은 fxAlertStreamExecutor 에서 해 요청 스레드를 붙잡지 않는다
 * - 타임아웃·완료·오류·전송 실패면 목록에서 뺀다. 종료(재배포)가 시작되면 열린 연결을 모두 닫는다(graceful shutdown 이 기다리지 않게)
 * - 로그에는 userId·연결 수·alertId 만 남긴다(환율·목표 값 없음)
 */
@Service
public class FxAlertStreamService {

    static final String READY = "ready";
    static final String TRIGGERED = "fx-alert-triggered";
    /** SSE 주석 한 줄 ": ping". 이벤트가 아니라 앱 SSE 클라이언트는 무시한다. */
    private static final String PING = " ping";

    private static final Logger log = LoggerFactory.getLogger(FxAlertStreamService.class);

    private final FxAlertRepository repository;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final FxAlertStreamProperties properties;
    private final ConcurrentHashMap<Long, List<Connection>> connections = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public FxAlertStreamService(FxAlertRepository repository, ObjectMapper objectMapper,
                                @Qualifier("fxAlertStreamExecutor") ExecutorService executor,
                                FxAlertStreamProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.properties = properties;
    }

    /** 연결 1개. closed 로 목록에서 한 번만 빼고 로그도 한 번만 남긴다. */
    private static final class Connection {

        private final long id;
        private final long userId;
        private final SseEmitter emitter;
        private final long openedNanos = System.nanoTime();
        private final AtomicBoolean closed = new AtomicBoolean();

        Connection(long id, long userId, SseEmitter emitter) {
            this.id = id;
            this.userId = userId;
            this.emitter = emitter;
        }
    }

    public SseEmitter connect(long userId) {
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        Connection connection = new Connection(sequence.incrementAndGet(), userId, emitter);
        emitter.onCompletion(() -> remove(connection, "COMPLETED"));
        // 직접 닫아야 AsyncRequestTimeoutException(전역 오류 처리기의 500 로그)으로 가지 않는다
        emitter.onTimeout(() -> close(connection, "TIMEOUT"));
        emitter.onError(e -> remove(connection, "ERROR"));
        // 등록 전에 넣어 ready 가 항상 첫 이벤트다(응답 준비 전 send 는 SseEmitter 가 모아 뒀다가 순서대로 보낸다)
        send(connection, SseEmitter.event().name(READY)
                .data(json(new FxAlertStreamReady(properties.pingInterval().toSeconds()))));
        register(connection);
        try {
            executor.execute(() -> backfill(connection));
        } catch (RejectedExecutionException e) {
            close(connection, "SHUTDOWN");
        }
        return emitter;
    }

    /**
     * 도달 판정이 커밋된 뒤 그 사용자의 열린 연결에 보낸다. 롤백되면 오지 않는다. 판정에 트랜잭션이 없으면(지금의 수집기·다시 판정)
     * 발행하자마자 온다(fallbackExecution, UPDATE 가 이미 커밋됨). 연결이 없으면 아무것도 하지 않는다(다음 연결 때 보충).
     * 읽기·전송은 실행기에서 해 판정 스레드를 붙잡지 않는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTriggered(FxAlertTriggeredEvent event) {
        if (connections.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> push(event.alertId()));
        } catch (RejectedExecutionException e) {
            log.warn("fx alert stream push skipped alertId={} reason=SHUTDOWN", event.alertId());
        }
    }

    private void push(long alertId) {
        NotificationRow row;
        try {
            // 커밋 뒤 다시 읽는다. 그사이 알렸거나(notified) 지웠으면 보내지 않는다
            row = repository.pendingNotification(alertId).orElse(null);
        } catch (DataAccessException e) {
            log.warn("fx alert stream push failed alertId={} error={}", alertId, e.getClass().getSimpleName());
            return;
        }
        if (row == null) {
            return;
        }
        List<Connection> targets = connections.getOrDefault(row.userId(), List.of());
        if (targets.isEmpty()) {
            return;
        }
        String json = json(FxAlertNotification.from(row));
        int sent = 0;
        for (Connection connection : targets) {
            if (send(connection, triggered(alertId, json))) {
                sent++;
            }
        }
        log.info("fx alert stream sent alertId={} userId={} connections={} sent={}", alertId, row.userId(),
                targets.size(), sent);
    }

    /**
     * 종료(재배포)가 시작되면 열린 연결을 먼저 닫는다. graceful shutdown 은 열린 스트림을 처리 중인 요청으로 보고 끝날 때까지
     * 기다려, 닫지 않으면 종료 제한 시간(기본 30초)까지 멈춘다. ContextClosedEvent 는 웹 서버가 멈추기 전에 온다.
     * 앱은 다시 연결해 보충을 받는다.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        connections.values().forEach(list -> list.forEach(connection -> close(connection, "SHUTDOWN")));
    }

    /** keep-alive. fxAlertStreamPinger 가 pingInterval 마다 부른다. 끊긴 연결은 여기서 전송 실패로 드러나 목록에서 빠진다. */
    public void pingAll() {
        try {
            for (List<Connection> list : connections.values()) {
                for (Connection connection : list) {
                    executor.execute(() -> send(connection, SseEmitter.event().comment(PING)));
                }
            }
        } catch (RuntimeException e) {
            // 예외가 나가면 다음 주기가 취소된다
            log.warn("fx alert stream ping failed error={}", e.getClass().getSimpleName());
        }
    }

    /** 지금 열린 연결 수(로그·테스트용). */
    public int openConnections() {
        return connections.values().stream().mapToInt(List::size).sum();
    }

    /** 그 사용자의 열린 연결 수(로그·테스트용). */
    public int openConnections(long userId) {
        return connections.getOrDefault(userId, List.of()).size();
    }

    private void register(Connection connection) {
        List<Connection> replaced = new ArrayList<>();
        connections.compute(connection.userId, (userId, current) -> {
            List<Connection> next = new ArrayList<>(current == null ? List.of() : current);
            next.add(connection);
            while (next.size() > properties.maxConnectionsPerUser()) {
                replaced.add(next.removeFirst());
            }
            return List.copyOf(next);
        });
        log.info("fx alert stream opened userId={} connectionId={} userConnections={} totalConnections={}",
                connection.userId, connection.id, openConnections(connection.userId), openConnections());
        replaced.forEach(old -> close(old, "REPLACED"));
    }

    /** 연결 직후 보충. 읽지 못하면 연결을 닫아 앱이 다시 연결하게 한다(보충 없이 열어 두지 않음). */
    private void backfill(Connection connection) {
        List<FxAlertNotification> pending;
        try {
            pending = repository.pendingNotifications(connection.userId).stream()
                    .map(FxAlertNotification::from).toList();
        } catch (DataAccessException e) {
            log.warn("fx alert stream backfill failed userId={} connectionId={} error={}", connection.userId,
                    connection.id, e.getClass().getSimpleName());
            close(connection, "BACKFILL_FAILED");
            return;
        }
        for (FxAlertNotification notification : pending) {
            if (!send(connection, triggered(notification.alertId(), json(notification)))) {
                return;
            }
        }
        if (!pending.isEmpty()) {
            log.info("fx alert stream backfill sent userId={} connectionId={} alerts={}", connection.userId,
                    connection.id, pending.size());
        }
    }

    /** 연결마다 새로 만든다(SseEventBuilder 는 보낼 때 내용을 덧붙여 다시 쓸 수 없다). */
    private static SseEmitter.SseEventBuilder triggered(long alertId, String json) {
        return SseEmitter.event().id(Long.toString(alertId)).name(TRIGGERED).data(json);
    }

    private boolean send(Connection connection, SseEmitter.SseEventBuilder event) {
        if (connection.closed.get()) {
            return false;
        }
        try {
            connection.emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            close(connection, "SEND_FAILED");
            return false;
        }
    }

    private void close(Connection connection, String reason) {
        remove(connection, reason);
        try {
            connection.emitter.complete();
        } catch (IllegalStateException ignored) {
            // 이미 끝남
        }
    }

    private void remove(Connection connection, String reason) {
        if (!connection.closed.compareAndSet(false, true)) {
            return;
        }
        connections.computeIfPresent(connection.userId, (userId, current) -> {
            List<Connection> next = current.stream().filter(c -> c != connection).toList();
            return next.isEmpty() ? null : next;
        });
        log.info("fx alert stream closed userId={} connectionId={} reason={} openSeconds={} totalConnections={}",
                connection.userId, connection.id, reason, (System.nanoTime() - connection.openedNanos) / 1_000_000_000,
                openConnections());
    }

    private String json(Object payload) {
        return objectMapper.writeValueAsString(payload);
    }
}
