package com.hanstep.chat.service;

import com.hanstep.chat.client.AgentStreamClient;
import com.hanstep.chat.client.AgentStreamClient.ChatRequest;
import com.hanstep.chat.client.AgentStreamClient.HistoryItem;
import com.hanstep.chat.client.SseLineParser;
import com.hanstep.chat.domain.ChatMessageStatus;
import com.hanstep.chat.domain.ChatRole;
import com.hanstep.chat.domain.ChatSessionStatus;
import com.hanstep.chat.domain.ChatSourceType;
import com.hanstep.chat.dto.ChatSource;
import com.hanstep.chat.dto.ChatStreamRequest;
import com.hanstep.chat.repository.ChatRepository;
import com.hanstep.chat.repository.ChatRepository.SessionRow;
import com.hanstep.chat.repository.ChatRepository.SourceRow;
import com.hanstep.chat.repository.ChatRepository.ToolCallRow;
import com.hanstep.common.ai.AiApiProperties;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.web.RequestIdFilter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CHT-04 메시지 스트리밍 중계. 앱 ↔ Spring 은 SseEmitter, Spring ↔ AI 는 INT-05(SSE)를 줄 단위로 읽어 이벤트를 바꿔 보낸다.
 *
 * <p>순서: 검증·소유권·중복 확인 → 짧은 트랜잭션으로 USER(COMPLETED)·ASSISTANT(STREAMING) 저장 → chat.started →
 * 실행기(chatStreamExecutor)에서 INT-05 호출. 스트림이 도는 동안 트랜잭션·DB 커넥션을 잡지 않고, 저장은 이벤트마다 짧게 한다.
 * delta 는 메모리에만 쌓고 ASSISTANT 행은 끝날 때 한 번만 갱신한다. 앱 연결이 끊기면 AI 입력 스트림을 닫아 생성을 멈추게 한다.
 * 로그에는 runId·sessionId·지연·상태·오류 코드만 남긴다(질문·답변 원문, 금액 금지).
 */
@Service
public class ChatStreamService {

    /** history 로 보내는 질문·답변 쌍 수(메시지는 최대 2배). */
    static final int HISTORY_PAIRS = 3;
    static final String UPSTREAM_ERROR = ErrorCode.AI_UPSTREAM_ERROR.name();
    private static final long EMITTER_MARGIN_MS = 10_000;
    private static final Set<String> TOOL_STATUSES = Set.of("SUCCEEDED", "FAILED", "TIMEOUT", "REJECTED");
    private static final String ERROR_MESSAGE = "Could not finish the reply. Please try again.";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final ChatSessionService chatSessionService;
    private final ChatRepository chatRepository;
    private final AgentStreamClient agentStreamClient;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executor;
    private final long emitterTimeoutMs;

    public ChatStreamService(ChatSessionService chatSessionService, ChatRepository chatRepository,
                             AgentStreamClient agentStreamClient, TransactionTemplate transactionTemplate,
                             @Qualifier("chatStreamExecutor") ExecutorService executor, AiApiProperties ai) {
        this.chatSessionService = chatSessionService;
        this.chatRepository = chatRepository;
        this.agentStreamClient = agentStreamClient;
        this.transactionTemplate = transactionTemplate;
        this.executor = executor;
        this.emitterTimeoutMs = ai.streamReadTimeout().toMillis() + EMITTER_MARGIN_MS;
    }

    // 앱으로 보내는 이벤트(data 는 JSON 한 줄)
    record Started(String runId, long userMessageId, long assistantMessageId) {
    }

    record ToolCall(String runId, int order, String toolName, String status, ResultRef resultRef, Integer latencyMs) {
    }

    record ResultRef(String type, Long id) {
    }

    record Delta(String runId, String text) {
    }

    record Sources(String runId, List<ChatSource> sources) {
    }

    record Done(String runId, long assistantMessageId, JsonNode blocks, int latencyMs) {
    }

    record ErrorEvent(String runId, String code, String message) {
    }

    /**
     * 스트림 시작 전 오류(404·409)는 예외로 던져 일반 JSON 오류로 응답한다.
     *
     * @param receivedNanos 요청 수신 시각(System.nanoTime). springFirstDeltaMs·latency_ms 기준
     */
    public SseEmitter start(Long userId, Long sessionId, ChatStreamRequest request, long receivedNanos) {
        SessionRow session = chatSessionService.ownedSession(userId, sessionId);
        if (session.status() != ChatSessionStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String clientMessageId = request.clientMessageId();
        chatRepository.findMessageIdByClientId(sessionId, clientMessageId).ifPresent(id -> {
            throw duplicate(id);
        });

        String language = chatRepository.userLanguage(userId);
        // 답변이 완료된 쌍만 보내 USER 로 시작하고 USER·ASSISTANT 가 번갈아 오게 한다(실패·끊긴 질문은 빠짐)
        List<HistoryItem> history = new ArrayList<>();
        for (String[] pair : chatRepository.recentCompletedPairs(sessionId, HISTORY_PAIRS)) {
            history.add(new HistoryItem(ChatRole.USER.name(), pair[0]));
            history.add(new HistoryItem(ChatRole.ASSISTANT.name(), pair[1]));
        }
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        String message = IdentifierMasker.mask(request.content());
        Instant now = ChatSessionService.now();
        long[] ids;
        try {
            ids = transactionTemplate.execute(status -> {
                long userMessageId = chatRepository.insertMessage(sessionId, ChatRole.USER, message,
                        ChatMessageStatus.COMPLETED, clientMessageId, runId, null, now);
                long assistantMessageId = chatRepository.insertMessage(sessionId, ChatRole.ASSISTANT, "",
                        ChatMessageStatus.STREAMING, null, runId, userMessageId, now);
                chatRepository.touchSession(sessionId, now);
                return new long[] {userMessageId, assistantMessageId};
            });
        } catch (DuplicateKeyException e) {
            // 동시에 같은 clientMessageId 가 들어와 유니크 키에 걸림
            throw duplicate(chatRepository.findMessageIdByClientId(sessionId, clientMessageId).orElse(null));
        }
        ChatRequest agentRequest = new ChatRequest(runId, userId, sessionId, language, history, message);
        return new Run(runId, sessionId, ids[0], ids[1], receivedNanos, agentRequest).start();
    }

    private static BusinessException duplicate(Long userMessageId) {
        Map<String, Object> details = new HashMap<>();
        details.put("userMessageId", userMessageId);
        return new BusinessException(ErrorCode.DUPLICATE_REQUEST, details);
    }

    /** 답변 1개의 중계 상태. 끝 상태(COMPLETED·FAILED·PARTIAL)는 finished 로 한 번만 기록한다. */
    private final class Run {

        private final String runId;
        private final long sessionId;
        private final long userMessageId;
        private final long assistantMessageId;
        private final long receivedNanos;
        private final ChatRequest request;
        private final String requestId = RequestIdFilter.current();
        private final SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        private final AgentStreamClient.Handle handle = new AgentStreamClient.Handle();
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean clientGone;
        private volatile long firstDeltaNanos;
        private volatile String finalStatus = ChatMessageStatus.STREAMING.name();
        private volatile String finalErrorCode;
        private volatile JsonNode aiTimings;
        private volatile JsonNode aiUsage;

        Run(String runId, long sessionId, long userMessageId, long assistantMessageId, long receivedNanos,
            ChatRequest request) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.userMessageId = userMessageId;
            this.assistantMessageId = assistantMessageId;
            this.receivedNanos = receivedNanos;
            this.request = request;
        }

        SseEmitter start() {
            // done 전에 완료·타임아웃·오류 콜백이 오면 앱 연결이 끊긴 것
            emitter.onCompletion(this::clientGone);
            emitter.onTimeout(this::clientGone);
            emitter.onError(e -> clientGone());
            send("chat.started", new Started(runId, userMessageId, assistantMessageId));
            try {
                executor.execute(this::relay);
            } catch (RejectedExecutionException e) {
                fail(UPSTREAM_ERROR);
                logSummary();
            }
            return emitter;
        }

        private void clientGone() {
            if (!finished.get()) {
                clientGone = true;
                handle.close();
            }
        }

        private void relay() {
            if (requestId != null) {
                MDC.put(RequestIdFilter.MDC_KEY, requestId);
            }
            try {
                agentStreamClient.stream(request, handle, this::onEvent);
                if (!finished.get()) {
                    if (clientGone) {
                        partial();
                    } else {
                        log.warn("chat stream ended without done runId={} sessionId={}", runId, sessionId);
                        fail(UPSTREAM_ERROR);
                    }
                }
            } catch (RuntimeException e) {
                if (clientGone) {
                    partial();
                } else {
                    boolean upstream = e instanceof RestClientException
                            || e instanceof AgentStreamClient.AgentHttpException;
                    log.warn("chat stream failed runId={} sessionId={} error={}", runId, sessionId,
                            e.getClass().getSimpleName());
                    fail(upstream ? UPSTREAM_ERROR : ErrorCode.INTERNAL_ERROR.name());
                }
            } finally {
                // STREAMING 으로 남는 경로가 없게
                if (!finished.get()) {
                    fail(UPSTREAM_ERROR);
                }
                logSummary();
                MDC.remove(RequestIdFilter.MDC_KEY);
            }
        }

        private boolean onEvent(SseLineParser.Event event) {
            if (clientGone) {
                return false;
            }
            switch (event.name()) {
                case "agent.tool_call" -> toolCall(parse(event));
                case "agent.delta" -> delta(parse(event));
                case "agent.sources" -> sources(parse(event));
                case "agent.done" -> {
                    done(parse(event));
                    return false;
                }
                case "agent.error" -> {
                    agentError(parse(event));
                    return false;
                }
                default -> {
                    // 모르는 이벤트는 무시
                }
            }
            return !clientGone;
        }

        private void toolCall(JsonNode data) {
            int order = data.path("order").asInt(0);
            String toolName = text(data, "toolName");
            String status = text(data, "status");
            JsonNode arguments = data.get("arguments");
            JsonNode ref = data.get("resultRef");
            ResultRef resultRef = ref == null || !ref.isObject() ? null
                    : new ResultRef(text(ref, "type"), longOrNull(ref, "id"));
            Integer latencyMs = intOrNull(data, "latencyMs");
            if (toolName != null && TOOL_STATUSES.contains(status)) {
                try {
                    chatRepository.insertToolCall(new ToolCallRow(assistantMessageId, runId, order, cut(toolName, 60),
                            arguments == null || arguments.isNull() ? null : arguments.toString(), status,
                            resultRef == null ? null : cut(resultRef.type(), 40),
                            resultRef == null ? null : resultRef.id(), latencyMs, cut(text(data, "errorCode"), 50)));
                } catch (DataAccessException e) {
                    log.warn("chat tool_call not saved runId={} order={} error={}", runId, order,
                            e.getClass().getSimpleName());
                }
            } else {
                log.warn("chat tool_call skipped runId={} order={} status={}", runId, order, status);
            }
            send("chat.tool_call", new ToolCall(runId, order, toolName, status, resultRef, latencyMs));
        }

        private void delta(JsonNode data) {
            String text = text(data, "text");
            if (text == null) {
                return;
            }
            buffer.append(text);
            if (send("chat.delta", new Delta(runId, text)) && firstDeltaNanos == 0) {
                firstDeltaNanos = System.nanoTime();
            }
        }

        private void sources(JsonNode data) {
            List<SourceRow> rows = new ArrayList<>();
            for (JsonNode s : data.path("sources")) {
                String type = text(s, "type");
                Long id = longOrNull(s, "id");
                if (ChatSourceType.find(type).isEmpty() || id == null) {
                    log.warn("chat source skipped runId={} type={}", runId, type);
                    continue;
                }
                rows.add(new SourceRow(type, id, cut(text(s, "chunkRef"), 100), cut(text(s, "pageLabel"), 50)));
            }
            try {
                chatRepository.insertSources(assistantMessageId, rows);
            } catch (DataAccessException e) {
                // tool_call 과 같게: 근거 저장이 실패해도 답변은 계속한다
                log.warn("chat sources not saved runId={} count={} error={}", runId, rows.size(),
                        e.getClass().getSimpleName());
            }
            send("chat.sources", new Sources(runId, rows.stream()
                    .map(r -> new ChatSource(r.type(), r.id(), r.chunkRef(), r.pageLabel())).toList()));
        }

        private void done(JsonNode data) {
            String content = text(data, "content");
            JsonNode blocks = data.get("blocks");
            JsonNode storedBlocks = blocks == null || blocks.isNull() ? null : blocks;
            aiTimings = data.get("timings");
            aiUsage = data.get("usage");
            int latencyMs = elapsedMs(receivedNanos);
            if (!finish(ChatMessageStatus.COMPLETED, content != null ? content : buffer.toString(),
                    storedBlocks == null ? null : storedBlocks.toString(), cut(text(data, "modelId"), 60),
                    cut(text(data, "promptVersion"), 40), latencyMs, null)) {
                return;
            }
            send("chat.done", new Done(runId, assistantMessageId, storedBlocks, latencyMs));
            complete();
        }

        /** 앱에는 AI_UPSTREAM_ERROR 로 통일하고, AI 가 준 코드는 error_code·로그에만 남긴다(AI 코드가 바뀌어도 앱 영향 없음). */
        private void agentError(JsonNode data) {
            String aiCode = cut(text(data, "code"), 50);
            fail(UPSTREAM_ERROR, aiCode == null ? UPSTREAM_ERROR : aiCode);
        }

        private void fail(String code) {
            fail(code, code);
        }

        /** FAILED 저장 + chat.error. content 는 그때까지 받은 조각. appCode 는 앱에, storedCode 는 error_code 에. */
        private void fail(String appCode, String storedCode) {
            if (finish(ChatMessageStatus.FAILED, buffer.toString(), null, null, null, null, storedCode)) {
                send("chat.error", new ErrorEvent(runId, appCode, ERROR_MESSAGE));
                complete();
            }
        }

        /** 앱 연결이 끊김: PARTIAL 저장. 보낼 곳이 없으므로 이벤트는 없다. */
        private void partial() {
            if (finish(ChatMessageStatus.PARTIAL, buffer.toString(), null, null, null, null, null)) {
                complete();
            }
        }

        /** ASSISTANT 행 끝 상태 기록(+ 대화방 last_message_at). 이미 끝났으면 false. */
        private boolean finish(ChatMessageStatus status, String content, String blocksJson, String modelId,
                               String promptVersion, Integer latencyMs, String errorCode) {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            finalStatus = status.name();
            finalErrorCode = errorCode;
            try {
                transactionTemplate.executeWithoutResult(tx -> {
                    chatRepository.finishMessage(assistantMessageId, status, IdentifierMasker.mask(content), blocksJson,
                            modelId, promptVersion, latencyMs, errorCode);
                    chatRepository.touchSession(sessionId, ChatSessionService.now());
                });
            } catch (DataAccessException e) {
                log.error("chat message not finished runId={} sessionId={} status={} error={}", runId, sessionId,
                        status, e.getClass().getSimpleName());
            }
            return true;
        }

        private boolean send(String name, Object payload) {
            try {
                emitter.send(SseEmitter.event().name(name).data(MAPPER.writeValueAsString(payload)));
                return true;
            } catch (IOException | IllegalStateException e) {
                clientGone();
                return false;
            }
        }

        private void complete() {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // 이미 끝남
            }
        }

        /** 답변마다 한 줄. springFirstDeltaMs − aiFirstDeltaMs 가 Spring 중계가 더한 지연. */
        private void logSummary() {
            Long springFirstDeltaMs = firstDeltaNanos == 0 ? null : (firstDeltaNanos - receivedNanos) / 1_000_000;
            Long aiFirstDeltaMs = longOrNull(aiTimings, "firstDeltaMs");
            log.info("chat run finished runId={} sessionId={} status={} errorCode={} springFirstDeltaMs={} "
                            + "springTotalMs={} aiFirstDeltaMs={} aiTotalMs={} aiToolMs={} relayFirstDeltaMs={} "
                            + "inputTokens={} outputTokens={} cacheReadInputTokens={} cacheCreationInputTokens={} "
                            + "llmCalls={}",
                    runId, sessionId, finalStatus, finalErrorCode, springFirstDeltaMs, elapsedMs(receivedNanos),
                    aiFirstDeltaMs, longOrNull(aiTimings, "totalMs"), longOrNull(aiTimings, "toolMs"),
                    springFirstDeltaMs == null || aiFirstDeltaMs == null ? null : springFirstDeltaMs - aiFirstDeltaMs,
                    longOrNull(aiUsage, "inputTokens"), longOrNull(aiUsage, "outputTokens"),
                    longOrNull(aiUsage, "cacheReadInputTokens"), longOrNull(aiUsage, "cacheCreationInputTokens"),
                    longOrNull(aiUsage, "llmCalls"));
        }
    }

    private static JsonNode parse(SseLineParser.Event event) {
        try {
            return MAPPER.readTree(event.data());
        } catch (JacksonException e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.canConvertToLong() ? null : value.asLong();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.canConvertToInt() ? null : value.asInt();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static int elapsedMs(long sinceNanos) {
        return (int) ((System.nanoTime() - sinceNanos) / 1_000_000);
    }
}
