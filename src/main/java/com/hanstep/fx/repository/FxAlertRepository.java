package com.hanstep.fx.repository;

import com.hanstep.fx.domain.FxAlertStatus;
import com.hanstep.fx.domain.FxComparator;
import com.hanstep.fx.domain.FxRateType;
import com.hanstep.fx.domain.FxSource;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** fx_alerts 와 알림에 쓰는 exchange_rates 조회. 시각 칸은 UTC. */
@Repository
public class FxAlertRepository {

    // unit_amount 는 그 통화 최신 행(기준일, 같은 날이면 마지막 시각) 값. 송금 견적(RemittanceQuoteService)과 같은 순서.
    // triggered_rate 는 TRIGGERED 일 때만 도달 판정에 쓴 행의 rate_type 값
    private static final String SELECT_ALERT = """
            SELECT a.id, a.currency, a.rate_type, a.comparator, a.target_rate, a.status, a.created_at, a.triggered_at,
                   a.notified_at,
                   (SELECT e.unit_amount FROM exchange_rates e WHERE e.currency = a.currency
                    ORDER BY e.rate_date DESC, e.rate_at DESC LIMIT 1) AS unit_amount,
                   CASE WHEN a.status = 'TRIGGERED' THEN
                     CASE a.rate_type WHEN 'SEND' THEN t.send_rate ELSE t.base_rate END
                   END AS triggered_rate
            FROM fx_alerts a
            LEFT JOIN exchange_rates t ON t.id = a.triggered_rate_id
            """;
    // 아직 사용자에게 알리지 않은 도달(TRIGGERED && notified_at IS NULL). 고시 단위·기준일·출처는 도달 판정에 쓴 행 값
    private static final String SELECT_PENDING_NOTIFICATION = """
            SELECT a.id, a.user_id, a.currency, a.rate_type, a.comparator, a.target_rate, a.triggered_at,
                   t.unit_amount, CASE a.rate_type WHEN 'SEND' THEN t.send_rate ELSE t.base_rate END AS triggered_rate,
                   t.rate_date, t.source
            FROM fx_alerts a
            JOIN exchange_rates t ON t.id = a.triggered_rate_id
            WHERE a.status = 'TRIGGERED' AND a.notified_at IS NULL
            """;

    private final JdbcTemplate jdbc;

    public FxAlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AlertRow(long id, String currency, FxRateType rateType, FxComparator comparator,
                           BigDecimal targetRate, Integer unitAmount, FxAlertStatus status, Instant createdAt,
                           Instant triggeredAt, BigDecimal triggeredRate, Instant notifiedAt) {
    }

    /** 아직 알리지 않은 도달 1건. 값은 모두 도달 판정에 쓴 환율 행(triggered_rate_id) 기준이다. */
    public record NotificationRow(long id, long userId, String currency, FxRateType rateType, FxComparator comparator,
                                  BigDecimal targetRate, int unitAmount, BigDecimal triggeredRate, Instant triggeredAt,
                                  LocalDate rateDate, FxSource source) {
    }

    /** 도달 판정 대상 ACTIVE 알림. */
    public record ActiveAlert(long id, FxRateType rateType, FxComparator comparator, BigDecimal targetRate,
                              Instant createdAt) {
    }

    /**
     * 도달 판정에 쓰는 실데이터 환율 1행(is_mock = FALSE). sendRate 는 원천에 없으면 null.
     * createdAt 은 그 행을 처음 저장한 시각(exchange_rates.created_at)이고 값이 바뀌어도(UPDATE) 그대로다.
     */
    public record JudgeRate(long id, String currency, Instant rateAt, BigDecimal baseRate, BigDecimal sendRate,
                            Instant createdAt) {
    }

    /** 그 통화 최신 행의 고시 단위·출처와, 송금 보낼 때 환율(send_rate)이 있는 행이 하나라도 있는지. */
    public record CurrencyRate(int unitAmount, String source, boolean hasSendRate) {
    }

    /** 환율 행이 없는 통화면 빈 값. */
    public Optional<CurrencyRate> currencyRate(String currency) {
        return jdbc.query("""
                        SELECT e.unit_amount, e.source,
                               EXISTS (SELECT 1 FROM exchange_rates s
                                       WHERE s.currency = e.currency AND s.send_rate IS NOT NULL) AS has_send_rate
                        FROM exchange_rates e
                        WHERE e.currency = ?
                        ORDER BY e.rate_date DESC, e.rate_at DESC
                        LIMIT 1""",
                (rs, i) -> new CurrencyRate(rs.getInt("unit_amount"), rs.getString("source"),
                        rs.getBoolean("has_send_rate")),
                currency).stream().findFirst();
    }

    /** ACTIVE 1행. 같은 사용자·통화·방향의 ACTIVE 가 있으면 uq_fx_alerts_active 에 걸려 DuplicateKeyException. */
    public long insert(long userId, String currency, FxRateType rateType, FxComparator comparator,
                       BigDecimal targetRate, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO fx_alerts (user_id, currency, rate_type, "
                    + "comparator, target_rate, created_at) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, currency);
            ps.setString(3, rateType.name());
            ps.setString(4, comparator.name());
            ps.setBigDecimal(5, targetRate);
            ps.setObject(6, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public Optional<Long> findActiveId(long userId, String currency, FxComparator comparator) {
        return jdbc.queryForList("SELECT id FROM fx_alerts WHERE user_id = ? AND currency = ? AND comparator = ? "
                        + "AND status = 'ACTIVE'", Long.class, userId, currency, comparator.name())
                .stream().findFirst();
    }

    /** 본인 알림 1건(상태 무관). */
    public Optional<AlertRow> find(long userId, long alertId) {
        return jdbc.query(SELECT_ALERT + "WHERE a.id = ? AND a.user_id = ?", FxAlertRepository::alert,
                alertId, userId).stream().findFirst();
    }

    /** 본인의 ACTIVE·TRIGGERED 알림. 최신순(같은 시각이면 id 큰 순). */
    public List<AlertRow> listVisible(long userId) {
        return jdbc.query(SELECT_ALERT + "WHERE a.user_id = ? AND a.status IN ('ACTIVE', 'TRIGGERED') "
                + "ORDER BY a.created_at DESC, a.id DESC", FxAlertRepository::alert, userId);
    }

    /** 본인 알림을 CANCELLED 로. 바꾼 행 수(없거나 남의 것이거나 이미 CANCELLED 면 0). */
    public int cancel(long userId, long alertId) {
        return jdbc.update("UPDATE fx_alerts SET status = 'CANCELLED' WHERE id = ? AND user_id = ? "
                + "AND status <> 'CANCELLED'", alertId, userId);
    }

    /** 그 통화의 ACTIVE 알림(ix_fx_alerts_active). TRIGGERED·CANCELLED 는 다시 판정하지 않는다. */
    public List<ActiveAlert> activeAlerts(String currency) {
        return jdbc.query("SELECT id, rate_type, comparator, target_rate, created_at FROM fx_alerts "
                        + "WHERE status = 'ACTIVE' AND currency = ? ORDER BY id",
                (rs, i) -> new ActiveAlert(rs.getLong("id"), FxRateType.valueOf(rs.getString("rate_type")),
                        FxComparator.valueOf(rs.getString("comparator")), rs.getBigDecimal("target_rate"),
                        instant(rs, "created_at")),
                currency);
    }

    /** ACTIVE 알림이 있는 통화. */
    public List<String> activeCurrencies() {
        return jdbc.queryForList("SELECT DISTINCT currency FROM fx_alerts WHERE status = 'ACTIVE' ORDER BY currency",
                String.class);
    }

    /** ids 중 실데이터 행만(Mock 은 빼고), rate_at·id 오름차순. */
    public List<JudgeRate> realRates(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String marks = String.join(", ", Collections.nCopies(ids.size(), "?"));
        return jdbc.query("SELECT id, currency, rate_at, base_rate, send_rate, created_at FROM exchange_rates "
                + "WHERE id IN (" + marks + ") AND NOT is_mock ORDER BY rate_at, id", FxAlertRepository::judgeRate,
                ids.toArray());
    }

    /**
     * ACTIVE 일 때만 TRIGGERED 로 바꾼다(조건부 UPDATE). 바꾼 행 수. 두 판정이 동시에 와도 InnoDB 가 행을 잠그고 늦은 쪽은
     * 최신 커밋 값(TRIGGERED)으로 조건을 다시 봐서 0 이 된다. 그사이 지워졌어도(CANCELLED) 0.
     */
    public int trigger(long alertId, long rateId, Instant now) {
        return jdbc.update("UPDATE fx_alerts SET status = 'TRIGGERED', triggered_rate_id = ?, triggered_at = ? "
                + "WHERE id = ? AND status = 'ACTIVE'", rateId, LocalDateTime.ofInstant(now, ZoneOffset.UTC), alertId);
    }

    public boolean exists(long userId, long alertId) {
        return !jdbc.queryForList("SELECT id FROM fx_alerts WHERE id = ? AND user_id = ?", Long.class,
                alertId, userId).isEmpty();
    }

    /** 본인의 아직 알리지 않은 도달. 도달 시각 최신순(같으면 id 큰 순). CANCELLED 는 빠진다. */
    public List<NotificationRow> pendingNotifications(long userId) {
        return jdbc.query(SELECT_PENDING_NOTIFICATION + "AND a.user_id = ? ORDER BY a.triggered_at DESC, a.id DESC",
                FxAlertRepository::notification, userId);
    }

    /**
     * TRIGGERED 이고 아직 비어 있을 때만 notified_at 을 채운다(조건부 UPDATE). 바꾼 행 수.
     * 이미 채워졌거나(처음 시각 유지), 없거나, 남의 것이거나, TRIGGERED 가 아니면 0.
     */
    public int markNotified(long userId, long alertId, Instant now) {
        return jdbc.update("UPDATE fx_alerts SET notified_at = ? WHERE id = ? AND user_id = ? "
                        + "AND status = 'TRIGGERED' AND notified_at IS NULL",
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), alertId, userId);
    }

    private static AlertRow alert(ResultSet rs, int rowNum) throws SQLException {
        return new AlertRow(rs.getLong("id"), rs.getString("currency"), FxRateType.valueOf(rs.getString("rate_type")),
                FxComparator.valueOf(rs.getString("comparator")), rs.getBigDecimal("target_rate"),
                rs.getObject("unit_amount", Integer.class), FxAlertStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"), instant(rs, "triggered_at"), rs.getBigDecimal("triggered_rate"),
                instant(rs, "notified_at"));
    }

    private static NotificationRow notification(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("currency"),
                FxRateType.valueOf(rs.getString("rate_type")), FxComparator.valueOf(rs.getString("comparator")),
                rs.getBigDecimal("target_rate"), rs.getInt("unit_amount"), rs.getBigDecimal("triggered_rate"),
                instant(rs, "triggered_at"), rs.getObject("rate_date", LocalDate.class),
                FxSource.valueOf(rs.getString("source")));
    }

    private static JudgeRate judgeRate(ResultSet rs, int rowNum) throws SQLException {
        return new JudgeRate(rs.getLong("id"), rs.getString("currency"), instant(rs, "rate_at"),
                rs.getBigDecimal("base_rate"), rs.getBigDecimal("send_rate"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
