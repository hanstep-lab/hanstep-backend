package com.hanstep.fx.repository;

import com.hanstep.fx.config.FxRatesProperties;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 통화별 환율 읽기를 한 곳에 모은다(#123). 환율 화면(FX-001)·송금 견적(TRF-02·INT-18)·챗봇 참고 환율(INT-14)이 같이 써서
 * 화면과 견적이 다른 값을 쓰지 않게 한다.
 * - 통화마다 우선 원천 하나(KOREAEXIM > MARKET_REF > MOCK)의 값만 쓴다(원천을 섞지 않음)
 * - 운영은 실데이터만(is_mock = FALSE). USD Mock 행도 source 가 KOREAEXIM 이라 원천만으로는 걸러지지 않는다.
 *   Mock 포함은 로컬 개발용 설정(hanstep.fx-rates.include-mock)
 * - 영업일별 값은 그 rate_date 의 마지막 rate_at 행
 * 시각 칸은 UTC. fetched_at 은 그 값을 원천에서 마지막으로 확인한 시각이다.
 */
@Repository
public class ExchangeRateReader {

    private static final String COLUMNS = "id, currency, source, provider, unit_amount, rate_date, rate_at, base_rate, "
            + "send_rate, receive_rate, is_mock, fetched_at, created_at";
    // 원천 우선순위. FIELD 는 목록에 없는 값에 0 을 주지만 source 는 이 세 값뿐이다(ENUM)
    private static final String PRIORITY = "FIELD(source, 'KOREAEXIM', 'MARKET_REF', 'MOCK')";

    private final JdbcTemplate jdbc;
    private final FxRatesProperties properties;

    public ExchangeRateReader(JdbcTemplate jdbc, FxRatesProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** 환율 1행. 환율은 unitAmount 외화당 원화. createdAt 은 처음 저장 시각(값이 바뀌어도 그대로, 알림 도달 판정 기준). */
    public record DailyRate(long id, String currency, String source, String provider, int unitAmount,
                            LocalDate rateDate, Instant rateAt, BigDecimal baseRate, BigDecimal sendRate,
                            BigDecimal receiveRate, boolean mock, Instant fetchedAt, Instant createdAt) {

        /** 출처 표시 문구. USD 는 은행이 정한 값이 아니라 수출입은행 고시 기준이다. */
        public String sourceLabel() {
            return switch (source) {
                case "KOREAEXIM" -> "수출입은행 고시 매매기준율";
                case "MARKET_REF" -> "ECB 기준환율 기반 참고값";
                default -> "Mock 환율(로컬 개발용)";
            };
        }
    }

    /** 그 통화 우선 원천의 최신 행(기준일, 같은 날이면 마지막 rate_at). 없으면 빈 값. */
    public Optional<DailyRate> latest(String currency) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exchange_rates WHERE currency = ? AND (NOT is_mock OR ?) "
                        + "ORDER BY " + PRIORITY + ", rate_date DESC, rate_at DESC, id DESC LIMIT 1",
                ExchangeRateReader::row, currency, properties.mockIncluded()).stream().findFirst();
    }

    /** source 의 영업일별 값(그 rate_date 의 마지막 rate_at 행), from~to(포함), 날짜 오름차순. 쿼리 1번. */
    public List<DailyRate> daily(String currency, String source, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT " + COLUMNS + " FROM (SELECT e.*, ROW_NUMBER() OVER (PARTITION BY e.rate_date "
                        + "ORDER BY e.rate_at DESC, e.id DESC) AS rn FROM exchange_rates e "
                        + "WHERE e.currency = ? AND e.rate_date BETWEEN ? AND ? AND e.source = ? AND (NOT e.is_mock OR ?)) d "
                        + "WHERE d.rn = 1 ORDER BY d.rate_date",
                ExchangeRateReader::row, currency, from, to, source, properties.mockIncluded());
    }

    /** source 의 가장 이른 기준일(기간 평균을 낼 만큼 데이터가 있는지 판단). 없으면 빈 값. */
    public Optional<LocalDate> earliestDate(String currency, String source) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT MIN(rate_date) FROM exchange_rates "
                        + "WHERE currency = ? AND source = ? AND (NOT is_mock OR ?)", LocalDate.class,
                currency, source, properties.mockIncluded()));
    }

    private static DailyRate row(ResultSet rs, int rowNum) throws SQLException {
        return new DailyRate(rs.getLong("id"), rs.getString("currency"), rs.getString("source"),
                rs.getString("provider"), rs.getInt("unit_amount"), rs.getObject("rate_date", LocalDate.class),
                utc(rs, "rate_at"), rs.getBigDecimal("base_rate"), rs.getBigDecimal("send_rate"),
                rs.getBigDecimal("receive_rate"), rs.getBoolean("is_mock"), utc(rs, "fetched_at"),
                utc(rs, "created_at"));
    }

    private static Instant utc(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
