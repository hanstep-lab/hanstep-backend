package com.hanstep.fx.repository;

import com.hanstep.fx.client.FxRate;
import com.hanstep.fx.domain.FxSource;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 수집기의 exchange_rates 저장. 키는 (currency, source, rate_at)(uq_exchange_rates_at). 시각 칸은 UTC.
 * fetched_at 은 그 값을 원천에서 마지막으로 확인한 시각이다(화면 "업데이트 확인", stale 판정).
 * 같은 rate_date·같은 값을 다시 받으면 새 행은 만들지 않는다. 1시간 수집(touchUnchanged=true)이면 그 행의 fetched_at 만
 * 고치고, 백필(false)이면 아무것도 쓰지 않는다(재실행해도 새 행 0, fetched_at 변경 0). 값이 바뀌었으면 값과 fetched_at 을 고친다.
 * 수집기는 is_mock = FALSE 행만 만든다. 같은 키에 Mock 행(04 시드)이 있으면 건드리지 않고 KEPT_MOCK 으로 돌려준다
 * (운영은 Mock 정리 SQL 을 먼저 실행한다).
 */
@Repository
public class ExchangeRateRepository {

    private final JdbcTemplate jdbc;

    public ExchangeRateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Outcome {
        INSERTED, UPDATED, UNCHANGED, KEPT_MOCK
    }

    public record Saved(long id, Outcome outcome) {
    }

    private record Existing(long id, boolean mock, int unitAmount, LocalDate rateDate, BigDecimal baseRate,
                            BigDecimal sendRate, BigDecimal receiveRate, String provider) {
    }

    public Saved upsert(FxSource source, String provider, FxRate rate, Instant fetchedAt, boolean touchUnchanged) {
        Optional<Existing> existing = find(source, rate);
        if (existing.isEmpty()) {
            try {
                return new Saved(insert(source, provider, rate, fetchedAt), Outcome.INSERTED);
            } catch (DuplicateKeyException e) {
                // 수집과 백필이 같은 행을 동시에 넣음. 먼저 들어간 행과 비교한다
                existing = find(source, rate);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }
        Existing row = existing.get();
        if (row.mock()) {
            return new Saved(row.id(), Outcome.KEPT_MOCK);
        }
        if (same(row, provider, rate)) {
            if (touchUnchanged) {
                jdbc.update("UPDATE exchange_rates SET fetched_at = ? WHERE id = ?", utc(fetchedAt), row.id());
            }
            return new Saved(row.id(), Outcome.UNCHANGED);
        }
        jdbc.update("""
                        UPDATE exchange_rates
                        SET unit_amount = ?, rate_date = ?, base_rate = ?, send_rate = ?, receive_rate = ?, provider = ?,
                            fetched_at = ?
                        WHERE id = ?""",
                rate.unitAmount(), rate.rateDate(), rate.baseRate(), rate.sendRate(), rate.receiveRate(), provider,
                utc(fetchedAt), row.id());
        return new Saved(row.id(), Outcome.UPDATED);
    }

    private Optional<Existing> find(FxSource source, FxRate rate) {
        return jdbc.query("""
                        SELECT id, is_mock, unit_amount, rate_date, base_rate, send_rate, receive_rate, provider
                        FROM exchange_rates
                        WHERE currency = ? AND source = ? AND rate_at = ?""",
                (rs, i) -> new Existing(rs.getLong("id"), rs.getBoolean("is_mock"), rs.getInt("unit_amount"),
                        rs.getObject("rate_date", LocalDate.class), rs.getBigDecimal("base_rate"),
                        rs.getBigDecimal("send_rate"), rs.getBigDecimal("receive_rate"), rs.getString("provider")),
                rate.currency(), source.name(), utc(rate.rateAt())).stream().findFirst();
    }

    private long insert(FxSource source, String provider, FxRate rate, Instant fetchedAt) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, send_rate,
                      receive_rate, source, provider, is_mock, fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rate.currency());
            ps.setInt(2, rate.unitAmount());
            ps.setObject(3, rate.rateDate());
            ps.setObject(4, utc(rate.rateAt()));
            ps.setBigDecimal(5, rate.baseRate());
            ps.setBigDecimal(6, rate.sendRate());
            ps.setBigDecimal(7, rate.receiveRate());
            ps.setString(8, source.name());
            ps.setString(9, provider);
            ps.setObject(10, utc(fetchedAt));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private static boolean same(Existing row, String provider, FxRate rate) {
        return row.unitAmount() == rate.unitAmount() && row.rateDate().equals(rate.rateDate())
                && sameValue(row.baseRate(), rate.baseRate()) && sameValue(row.sendRate(), rate.sendRate())
                && sameValue(row.receiveRate(), rate.receiveRate()) && Objects.equals(row.provider(), provider);
    }

    // DB 는 소수 4자리(22.3200)로 돌려주므로 자리 수가 아니라 값으로 비교한다
    private static boolean sameValue(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
