package com.hanstep.chat.service;

import com.hanstep.chat.dto.ChatMessageListResponse;
import com.hanstep.chat.dto.ChatSessionCreateRequest;
import com.hanstep.chat.dto.ChatSessionListResponse;
import com.hanstep.chat.dto.ChatSessionResponse;
import com.hanstep.chat.dto.ChatSource;
import com.hanstep.chat.dto.PageInfo;
import com.hanstep.chat.repository.ChatRepository;
import com.hanstep.chat.repository.ChatRepository.MessageRow;
import com.hanstep.chat.repository.ChatRepository.SessionRow;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** CHT-01·02·03. 남의 대화방·없는 대화방은 404. */
@Service
public class ChatSessionService {

    public static final int SESSION_PAGE_MAX = 100;
    public static final int MESSAGE_PAGE_MAX = 100;

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final ChatRepository chatRepository;

    public ChatSessionService(ChatRepository chatRepository) {
        this.chatRepository = chatRepository;
    }

    public ChatSessionResponse create(Long userId, ChatSessionCreateRequest request) {
        String title = request == null || !StringUtils.hasText(request.title()) ? null : request.title().strip();
        Instant now = now();
        long id = chatRepository.insertSession(userId, title, now);
        SessionRow row = chatRepository.findSession(id).orElseThrow();
        return new ChatSessionResponse(row.id(), row.title(), row.status(), kst(row.lastMessageAt()),
                kst(row.createdAt()));
    }

    public ChatSessionListResponse list(Long userId, String cursor, int size) {
        ChatCursor.checkSize(size, SESSION_PAGE_MAX);
        ChatCursor after = ChatCursor.decode(cursor);
        List<SessionRow> rows = chatRepository.listActiveSessions(userId, after == null ? null : after.at(),
                after == null ? null : after.id(), size + 1);
        boolean hasNext = rows.size() > size;
        List<SessionRow> page = hasNext ? rows.subList(0, size) : rows;
        SessionRow last = page.isEmpty() ? null : page.getLast();
        String next = hasNext ? new ChatCursor(last.sortAt(), last.id()).encode() : null;
        return new ChatSessionListResponse(page.stream()
                .map(r -> new ChatSessionListResponse.Item(r.id(), r.title(), r.status(), kst(r.lastMessageAt())))
                .toList(), new PageInfo(next, hasNext));
    }

    public ChatMessageListResponse messages(Long userId, Long sessionId, String cursor, int size) {
        ChatCursor.checkSize(size, MESSAGE_PAGE_MAX);
        ChatCursor before = ChatCursor.decode(cursor);
        ownedSession(userId, sessionId);
        List<MessageRow> rows = chatRepository.listMessagesDesc(sessionId, before == null ? null : before.at(),
                before == null ? null : before.id(), size + 1);
        boolean hasNext = rows.size() > size;
        List<MessageRow> page = new ArrayList<>(hasNext ? rows.subList(0, size) : rows);
        String next = hasNext ? new ChatCursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
        Collections.reverse(page);

        Map<Long, List<ChatRepository.SourceRow>> sources = chatRepository.sourcesOf(
                page.stream().map(MessageRow::id).toList());
        return new ChatMessageListResponse(page.stream().map(m -> new ChatMessageListResponse.Message(
                m.id(), m.role(), m.content(), json(m.blocksJson()), m.status(), m.clientMessageId(), m.runId(),
                m.replyToId(), sources.getOrDefault(m.id(), List.of()).stream()
                        .map(s -> new ChatSource(s.type(), s.id(), s.chunkRef(), s.pageLabel())).toList(),
                m.latencyMs(), kst(m.createdAt()))).toList(), new PageInfo(next, hasNext));
    }

    /** 본인 대화방. 없거나 남의 것이면 404. */
    SessionRow ownedSession(Long userId, Long sessionId) {
        return chatRepository.findSession(sessionId)
                .filter(s -> s.userId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** DATETIME(3) 에 맞춰 ms 로 자른 현재 시각(MySQL 반올림으로 커서가 어긋나지 않게). */
    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    static OffsetDateTime kst(Instant at) {
        return at == null ? null : at.atOffset(KST).truncatedTo(ChronoUnit.SECONDS);
    }

    private static JsonNode json(String raw) {
        return raw == null ? null : MAPPER.readTree(raw);
    }
}
