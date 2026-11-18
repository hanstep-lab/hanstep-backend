package com.hanstep.fx.service;

import com.hanstep.common.dto.Decimals;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.fx.config.FxRatesProperties;
import com.hanstep.fx.dto.FxRateHistoryResponse;
import com.hanstep.fx.dto.FxRateResponse;
import com.hanstep.fx.dto.FxRateResponse.PeriodAverage;
import com.hanstep.fx.repository.ExchangeRateReader;
import com.hanstep.fx.repository.ExchangeRateReader.DailyRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 환율 정보 화면(FX-001·FX-002, #123). DB(exchange_rates)만 읽는다. 장애로 새 값이 없으면 마지막 값을 주고 stale 로 알린다.
 * 화면 통화는 USD 와 모국 통화(국적 기준) 두 개뿐이다. 통화마다 우선 원천 하나의 값만 쓴다(ExchangeRateReader).
 * 기간 평균은 저장하지 않고 조회할 때 계산한다: 최근 12개월 영업일별 값을 쿼리 1번으로 읽어 앱에서 4개 기간을 나눈다.
 * 반올림: 환율·평균·차이는 소수 4자리, % 는 소수 2자리, 모두 HALF_UP. 응답은 끝자리 0 을 뗀다.
 */
@Service
public class FxRateService {

    public static final String USD = "USD";
    static final String REFERENCE_RATE_ONLY = "REFERENCE_RATE_ONLY";
    static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
    static final String LOWER = "LOWER";
    static final String HIGHER = "HIGHER";
    static final String SAME = "SAME";

    /** 기간 키(프론트 FX_PERIODS 와 같음) → 개월 수. 기간은 기준일부터 거슬러 N개월, 시작일 포함. */
    static final Map<String, Integer> PERIODS = periods();

    // 임시값(팀 확인 필요): 현재와 평균의 차이가 ±0.10% 이내면 SAME
    static final BigDecimal SAME_THRESHOLD_PCT = new BigDecimal("0.10");
    // 임시값(팀 확인 필요): MARKET_REF 는 마지막 확인 시각(fetched_at)이 3시간 넘게 지나면 stale(1시간 수집이 연속 실패)
    static final Duration MARKET_REF_STALE_AFTER = Duration.ofHours(3);
    // 임시값: 기간 시작일이 주말·연휴면 그 뒤 첫 고시일부터 값이 있으므로, 데이터 시작일이 기간 시작 + 7일 이내면 평균을 낸다
    static final int COVERAGE_GRACE_DAYS = 7;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final ExchangeRateReader reader;
    private final JdbcTemplate jdbc;
    private final FxRatesProperties properties;

    public FxRateService(ExchangeRateReader reader, JdbcTemplate jdbc, FxRatesProperties properties) {
        this.reader = reader;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** FX-001. currency 를 안 주면 모국 통화(없으면 USD). 화면 통화가 아니면 400, 데이터가 없으면 404. */
    public FxRateResponse rate(long userId, String currency) {
        List<String> screen = screenCurrencies(userId);
        String target = currency == null ? screen.getLast() : currency;
        checkScreenCurrency(screen, target);
        DailyRate latest = reader.latest(target).orElseThrow(() -> new BusinessException(ErrorCode.FX_RATE_NOT_FOUND));
        LocalDate asOf = latest.rateDate();
        List<DailyRate> days = reader.daily(target, latest.source(), asOf.minusMonths(12), asOf);
        LocalDate earliest = reader.earliestDate(target, latest.source()).orElse(asOf);

        BigDecimal current = latest.baseRate();
        DailyRate previous = days.size() >= 2 ? days.get(days.size() - 2) : null;
        BigDecimal change = previous == null ? null : current.subtract(previous.baseRate()).setScale(4, RoundingMode.HALF_UP);
        BigDecimal changeRate = previous == null ? null : percent(change, previous.baseRate());

        List<PeriodAverage> averages = new ArrayList<>();
        PERIODS.forEach((period, months) -> averages.add(average(period, asOf.minusMonths(months), days, earliest, current)));
        return new FxRateResponse(target, latest.unitAmount(), Decimals.plain(current), Decimals.plain(latest.sendRate()),
                Decimals.plain(latest.receiveRate()), Decimals.plain(change), Decimals.plain(changeRate), asOf,
                latest.sourceLabel(), latest.source(), latest.mock(), kst(latest.rateAt()), kst(latest.fetchedAt()),
                stale(latest, Instant.now()), notices(latest), averages, screen);
    }

    /** FX-002. period 는 1M·3M·6M·1Y(12M 도 1Y 로 받음). */
    public FxRateHistoryResponse history(long userId, String currency, String period) {
        String key = "12M".equals(period) ? "1Y" : period;
        Integer months = key == null ? null : PERIODS.get(key);
        if (months == null) {
            throw invalid("period", "must be one of " + String.join(", ", PERIODS.keySet()));
        }
        checkScreenCurrency(screenCurrencies(userId), currency);
        DailyRate latest = reader.latest(currency).orElseThrow(() -> new BusinessException(ErrorCode.FX_RATE_NOT_FOUND));
        LocalDate asOf = latest.rateDate();
        LocalDate start = asOf.minusMonths(months);
        List<DailyRate> days = reader.daily(currency, latest.source(), start, asOf);
        LocalDate earliest = reader.earliestDate(currency, latest.source()).orElse(asOf);

        PeriodAverage average = average(key, start, days, earliest, latest.baseRate());
        List<FxRateHistoryResponse.Point> points = days.stream()
                .map(d -> new FxRateHistoryResponse.Point(d.rateDate(), Decimals.plain(d.baseRate()))).toList();
        BigDecimal min = days.stream().map(DailyRate::baseRate).min(BigDecimal::compareTo).orElse(latest.baseRate());
        BigDecimal max = days.stream().map(DailyRate::baseRate).max(BigDecimal::compareTo).orElse(latest.baseRate());
        return new FxRateHistoryResponse(currency, key, points, Decimals.plain(min), Decimals.plain(max),
                average.avg(), Decimals.plain(latest.baseRate()), average.businessDays(), average.reason(),
                latest.source(), days.stream().anyMatch(DailyRate::mock), asOf);
    }

    /** USD + 모국 통화(visa_profiles.nationality_code). 국적이 없거나 매핑이 없으면 USD 만. */
    List<String> screenCurrencies(long userId) {
        List<String> nationality = jdbc.queryForList("SELECT nationality_code FROM visa_profiles WHERE user_id = ?",
                String.class, userId);
        List<String> screen = new ArrayList<>(List.of(USD));
        properties.homeCurrency(nationality.isEmpty() ? null : nationality.getFirst())
                .filter(c -> !USD.equals(c))
                .ifPresent(screen::add);
        return screen;
    }

    /**
     * start(포함)~기준일 영업일 값의 평균과 현재 값 비교. 데이터 시작일이 start + 7일보다 늦으면 기간보다 짧은 것이라
     * 평균을 내지 않는다(INSUFFICIENT_DATA).
     */
    static PeriodAverage average(String period, LocalDate start, List<DailyRate> days, LocalDate earliest,
                                 BigDecimal current) {
        List<BigDecimal> values = days.stream().filter(d -> !d.rateDate().isBefore(start)).map(DailyRate::baseRate)
                .toList();
        if (values.isEmpty() || earliest.isAfter(start.plusDays(COVERAGE_GRACE_DAYS))) {
            return new PeriodAverage(period, null, null, null, null, values.size(), INSUFFICIENT_DATA);
        }
        BigDecimal avg = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
        BigDecimal diff = current.subtract(avg).setScale(4, RoundingMode.HALF_UP);
        BigDecimal diffRate = percent(diff, avg);
        String direction = diffRate.abs().compareTo(SAME_THRESHOLD_PCT) <= 0 ? SAME
                : diff.signum() < 0 ? LOWER : HIGHER;
        return new PeriodAverage(period, Decimals.plain(avg), Decimals.plain(diff), Decimals.plain(diffRate), direction,
                values.size(), null);
    }

    /**
     * 오래된 값인지(임시 기준). MARKET_REF 는 마지막 확인 시각(fetched_at)이 3시간 넘게 지났으면,
     * 그 밖(KOREAEXIM·MOCK)은 기준일이 직전 영업일보다 이르면 true. 영업일은 주말만 뺀다(공휴일 뒤에는 true 가 될 수 있음).
     */
    static boolean stale(DailyRate latest, Instant now) {
        if ("MARKET_REF".equals(latest.source())) {
            return latest.fetchedAt().isBefore(now.minus(MARKET_REF_STALE_AFTER));
        }
        return latest.rateDate().isBefore(previousBusinessDay(now.atZone(KstDates.KST).toLocalDate()));
    }

    /** today 바로 전 평일. */
    static LocalDate previousBusinessDay(LocalDate today) {
        LocalDate day = today.minusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.minusDays(1);
        }
        return day;
    }

    private static Map<String, Integer> periods() {
        Map<String, Integer> periods = new LinkedHashMap<>();
        periods.put("1M", 1);
        periods.put("3M", 3);
        periods.put("6M", 6);
        periods.put("1Y", 12);
        return Collections.unmodifiableMap(periods);
    }

    private static List<String> notices(DailyRate latest) {
        return "MARKET_REF".equals(latest.source()) ? List.of(REFERENCE_RATE_ONLY) : List.of();
    }

    private static BigDecimal percent(BigDecimal diff, BigDecimal base) {
        return diff.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime kst(Instant at) {
        return at == null ? null : at.atOffset(KST).truncatedTo(ChronoUnit.SECONDS);
    }

    private static void checkScreenCurrency(List<String> screen, String currency) {
        if (currency == null || !screen.contains(currency)) {
            throw invalid("currency", "must be one of " + String.join(", ", screen));
        }
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
