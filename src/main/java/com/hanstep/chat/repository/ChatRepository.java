package com.hanstep.chat.repository;

import com.hanstep.chat.domain.ChatMessageStatus;
import com.hanstep.chat.domain.ChatRole;
import com.hanstep.chat.domain.ChatSessionStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * chat_sessions·chat_messages·chat_tool_calls·chat_message_sources SQL.
 * JPA 엔티티 대신 JdbcTemplate 을 쓴다. 스트림이 도는 동안 영속성 컨텍스트·커넥션을 잡지 않고, 저장은 문장마다 짧게 끝내기 위함.
 * 시각은 UTC 로 저장한다(다른 테이블과 같음).
 */
@Repository
public class ChatRepository {

    private static final String SESSION_COLUMNS = "id, user_id, title, status, last_message_at, created_at, "
            + "COALESCE(last_message_at, created_at) AS sort_at";
    private static final String MESSAGE_COLUMNS = "id, role, content, blocks_json, status, client_message_id, run_id, "
            + "reply_to_id, latency_ms, created_at";

    private final JdbcTemplate jdbc;

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SessionRow(Long id, Long userId, String title, ChatSessionStatus status, Instant lastMessageAt,
                             Instant createdAt, Instant sortAt) {
    }

    public record MessageRow(Long id, ChatRole role, String content, String blocksJson, ChatMessageStatus status,
                             String clientMessageId, String runId, Long replyToId, Integer latencyMs,
                             Instant createdAt) {
    }

    public record SourceRow(String type, Long id, String chunkRef, String pageLabel) {
    }

    public long insertSession(long userId, String title, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO chat_sessions (user_id, title, status, created_at) VALUES (?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setObject(3, utc(now));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public Optional<SessionRow> findSession(long sessionId) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM chat_sessions WHERE id = ?", ChatRepository::session,
                sessionId).stream().findFirst();
    }

    /** 본인의 ACTIVE 대화방. COALESCE(last_message_at, created_at) 내림차순 → id 내림차순, 커서 다음부터 limit 개. */
    public List<SessionRow> listActiveSessions(long userId, Instant cursorAt, Long cursorId, int limit) {
        String sql = "SELECT " + SESSION_COLUMNS + " FROM chat_sessions WHERE user_id = ? AND status = 'ACTIVE' ";
        if (cursorAt == null) {
            return jdbc.query(sql + "ORDER BY sort_at DESC, id DESC LIMIT ?", ChatRepository::session, userId, limit);
        }
        LocalDateTime at = utc(cursorAt);
        return jdbc.query(sql + "AND (COALESCE(last_message_at, created_at) < ? "
                        + "OR (COALESCE(last_message_at, created_at) = ? AND id < ?)) "
                        + "ORDER BY sort_at DESC, id DESC LIMIT ?",
                ChatRepository::session, userId, at, at, cursorId, limit);
    }

    /** (created_at, id) 내림차순으로 커서 이전 limit 개. 호출하는 쪽에서 오래된 순으로 뒤집는다. */
    public List<MessageRow> listMessagesDesc(long sessionId, Instant cursorAt, Long cursorId, int limit) {
        String sql = "SELECT " + MESSAGE_COLUMNS + " FROM chat_messages WHERE session_id = ? ";
        if (cursorAt == null) {
            return jdbc.query(sql + "ORDER BY created_at DESC, id DESC LIMIT ?", ChatRepository::message,
                    sessionId, limit);
        }
        LocalDateTime at = utc(cursorAt);
        return jdbc.query(sql + "AND (created_at < ? OR (created_at = ? AND id < ?)) "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                ChatRepository::message, sessionId, at, at, cursorId, limit);
    }

    /** 메시지들의 근거를 IN 쿼리 한 번으로. 근거가 없는 메시지는 키가 없다. */
    public Map<Long, List<SourceRow>> sourcesOf(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        String in = String.join(",", Collections.nCopies(messageIds.size(), "?"));
        Map<Long, List<SourceRow>> result = new LinkedHashMap<>();
        jdbc.query("SELECT message_id, source_type, source_id, chunk_ref, page_label FROM chat_message_sources "
                        + "WHERE message_id IN (" + in + ") ORDER BY id",
                rs -> {
                    result.computeIfAbsent(rs.getLong("message_id"), k -> new ArrayList<>())
                            .add(new SourceRow(rs.getString("source_type"), rs.getLong("source_id"),
                                    rs.getString("chunk_ref"), rs.getString("page_label")));
                }, messageIds.toArray());
        return result;
    }

    public record ToolCallRow(long messageId, String runId, int order, String toolName, String argumentsJson,
                              String status, String resultRefType, Long resultRefId, Integer latencyMs,
                              String errorCode) {
    }

    public Optional<Long> findMessageIdByClientId(long sessionId, String clientMessageId) {
        return jdbc.queryForList("SELECT id FROM chat_messages WHERE session_id = ? AND client_message_id = ?",
                Long.class, sessionId, clientMessageId).stream().findFirst();
    }

    /** 같은 대화방 COMPLETED 메시지 최근 limit 개(오래된 순, role·content). */
    public List<String[]> recentCompleted(long sessionId, int limit) {
        List<String[]> rows = new ArrayList<>(jdbc.query("SELECT role, content FROM chat_messages "
                        + "WHERE session_id = ? AND status = 'COMPLETED' ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, i) -> new String[] {rs.getString("role"), rs.getString("content")}, sessionId, limit));
        Collections.reverse(rows);
        return rows;
    }

    public String userLanguage(long userId) {
        return jdbc.queryForObject("SELECT preferred_language FROM users WHERE id = ?", String.class, userId);
    }

    /** USER·ASSISTANT 메시지 1행. 같은 대화방 같은 client_message_id 면 DuplicateKeyException. */
    public long insertMessage(long sessionId, ChatRole role, String content, ChatMessageStatus status,
                              String clientMessageId, String runId, Long replyToId, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO chat_messages (session_id, role, content, status, "
                    + "client_message_id, run_id, reply_to_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sessionId);
            ps.setString(2, role.name());
            ps.setString(3, content);
            ps.setString(4, status.name());
            ps.setString(5, clientMessageId);
            ps.setString(6, runId);
            ps.setObject(7, replyToId);
            ps.setObject(8, utc(now));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public void touchSession(long sessionId, Instant at) {
        jdbc.update("UPDATE chat_sessions SET last_message_at = ? WHERE id = ?", utc(at), sessionId);
    }

    public void insertToolCall(ToolCallRow row) {
        jdbc.update("INSERT INTO chat_tool_calls (message_id, run_id, call_order, tool_name, arguments_json, status, "
                        + "result_ref_type, result_ref_id, latency_ms, error_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.messageId(), row.runId(), row.order(), row.toolName(), row.argumentsJson(), row.status(),
                row.resultRefType(), row.resultRefId(), row.latencyMs(), row.errorCode());
    }

    public void insertSources(long messageId, List<SourceRow> sources) {
        if (sources.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO chat_message_sources (message_id, source_type, source_id, chunk_ref, page_label) "
                        + "VALUES (?, ?, ?, ?, ?)",
                sources.stream().map(s -> new Object[] {messageId, s.type(), s.id(), s.chunkRef(), s.pageLabel()})
                        .toList());
    }

    /** STREAMING 인 ASSISTANT 메시지를 끝 상태로. 이미 끝났으면 0(끝 상태는 한 번만 기록). */
    public int finishMessage(long messageId, ChatMessageStatus status, String content, String blocksJson,
                             String modelId, String promptVersion, Integer latencyMs, String errorCode) {
        return jdbc.update("UPDATE chat_messages SET status = ?, content = ?, blocks_json = ?, model_id = ?, "
                        + "prompt_version = ?, latency_ms = ?, error_code = ? WHERE id = ? AND status = 'STREAMING'",
                status.name(), content, blocksJson, modelId, promptVersion, latencyMs, errorCode, messageId);
    }

    private static SessionRow session(ResultSet rs, int i) throws SQLException {
        return new SessionRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"),
                ChatSessionStatus.valueOf(rs.getString("status")), instant(rs, "last_message_at"),
                instant(rs, "created_at"), instant(rs, "sort_at"));
    }

    private static MessageRow message(ResultSet rs, int i) throws SQLException {
        return new MessageRow(rs.getLong("id"), ChatRole.valueOf(rs.getString("role")), rs.getString("content"),
                rs.getString("blocks_json"), ChatMessageStatus.valueOf(rs.getString("status")),
                rs.getString("client_message_id"), rs.getString("run_id"), rs.getObject("reply_to_id", Long.class),
                rs.getObject("latency_ms", Integer.class), instant(rs, "created_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
