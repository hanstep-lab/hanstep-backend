package com.hanstep.fx.repository;

import com.hanstep.fx.domain.FxAlertStatus;
import com.hanstep.fx.domain.FxComparator;
import com.hanstep.fx.domain.FxRateType;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
                   (SELECT e.unit_amount FROM exchange_rates e WHERE e.currency = a.currency
                    ORDER BY e.rate_date DESC, e.rate_at DESC LIMIT 1) AS unit_amount,
                   CASE WHEN a.status = 'TRIGGERED' THEN
                     CASE a.rate_type WHEN 'SEND' THEN t.send_rate ELSE t.base_rate END
                   END AS triggered_rate
            FROM fx_alerts a
            LEFT JOIN exchange_rates t ON t.id = a.triggered_rate_id
            """;

    private final JdbcTemplate jdbc;

    public FxAlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AlertRow(long id, String currency, FxRateType rateType, FxComparator comparator,
                           BigDecimal targetRate, Integer unitAmount, FxAlertStatus status, Instant createdAt,
                           Instant triggeredAt, BigDecimal triggeredRate) {
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

    public boolean exists(long userId, long alertId) {
        return !jdbc.queryForList("SELECT id FROM fx_alerts WHERE id = ? AND user_id = ?", Long.class,
                alertId, userId).isEmpty();
    }

    private static AlertRow alert(ResultSet rs, int rowNum) throws SQLException {
        return new AlertRow(rs.getLong("id"), rs.getString("currency"), FxRateType.valueOf(rs.getString("rate_type")),
                FxComparator.valueOf(rs.getString("comparator")), rs.getBigDecimal("target_rate"),
                rs.getObject("unit_amount", Integer.class), FxAlertStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"), instant(rs, "triggered_at"), rs.getBigDecimal("triggered_rate"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
