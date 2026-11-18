package com.hanstep.fx.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.fx.dto.FxRateResponse.PeriodAverage;
import com.hanstep.fx.repository.ExchangeRateReader.DailyRate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 기간 평균·방향·데이터 부족·stale 판정(DB 없이). */
class FxRateServiceTest {

    private static final LocalDate START = LocalDate.of(2099, 2, 28);

    @Test
    void 평균은_소수_4자리_퍼센트는_2자리_HALF_UP() {
        // (1 + 2 + 2) ÷ 3 = 1.66666… → 1.6667, (2 − 1.6667) ÷ 1.6667 × 100 = 19.9976… → 20.00
        PeriodAverage average = FxRateService.average("1M", START,
                days("1", "2", "2"), START, new BigDecimal("2"));

        assertThat(average.avg()).isEqualByComparingTo("1.6667");
        assertThat(average.diff()).isEqualByComparingTo("0.3333");
        assertThat(average.diffRate()).isEqualByComparingTo("20.00");
        assertThat(average.direction()).isEqualTo("HIGHER");
        assertThat(average.businessDays()).isEqualTo(3);
    }

    @Test
    void 평균과의_차이가_0_10퍼센트_이내면_SAME() {
        assertThat(direction("1000.9")).isEqualTo("SAME");   // +0.09%
        assertThat(direction("1001")).isEqualTo("SAME");     // +0.10%
        assertThat(direction("1001.1")).isEqualTo("HIGHER"); // +0.11%
        assertThat(direction("999")).isEqualTo("SAME");      // −0.10%
        assertThat(direction("998.9")).isEqualTo("LOWER");   // −0.11%
    }

    @Test
    void 데이터_시작이_기간_시작_7일_뒤까지면_평균을_내고_그보다_늦으면_INSUFFICIENT_DATA() {
        List<DailyRate> days = days("1000", "1000");

        assertThat(FxRateService.average("1M", START, days, START.plusDays(7), BigDecimal.TEN).avg()).isNotNull();
        PeriodAverage late = FxRateService.average("1M", START, days, START.plusDays(8), BigDecimal.TEN);
        assertThat(late.avg()).isNull();
        assertThat(late.reason()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(late.businessDays()).isEqualTo(2);
    }

    @Test
    void 직전_영업일은_주말을_건너뛴다() {
        assertThat(FxRateService.previousBusinessDay(LocalDate.of(2026, 11, 23))).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(FxRateService.previousBusinessDay(LocalDate.of(2026, 11, 22))).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(FxRateService.previousBusinessDay(LocalDate.of(2026, 11, 24))).isEqualTo(LocalDate.of(2026, 11, 23));
    }

    @Test
    void 수출입은행_값은_기준일이_직전_영업일보다_이르면_stale() {
        Instant mondayNoon = Instant.parse("2026-11-23T03:00:00Z"); // 월요일 12:00 KST, 직전 영업일은 금요일 11/20

        assertThat(FxRateService.stale(row("KOREAEXIM", "2026-11-20", mondayNoon), mondayNoon)).isFalse();
        assertThat(FxRateService.stale(row("KOREAEXIM", "2026-11-19", mondayNoon), mondayNoon)).isTrue();
        assertThat(FxRateService.stale(row("MOCK", "2026-11-06", mondayNoon), mondayNoon)).isTrue();
    }

    @Test
    void 참고_환율은_마지막_확인_시각이_3시간_넘게_지나면_stale() {
        Instant now = Instant.parse("2026-11-18T03:00:00Z");

        assertThat(FxRateService.stale(row("MARKET_REF", "2026-11-17", now.minus(179, ChronoUnit.MINUTES)), now))
                .isFalse();
        assertThat(FxRateService.stale(row("MARKET_REF", "2026-11-17", now.minus(181, ChronoUnit.MINUTES)), now))
                .isTrue();
    }

    private static String direction(String current) {
        return FxRateService.average("1M", START, days("1000"), START, new BigDecimal(current)).direction();
    }

    private static List<DailyRate> days(String... rates) {
        return IntStream.range(0, rates.length)
                .mapToObj(i -> row("KOREAEXIM", START.plusDays(i).toString(), Instant.EPOCH, rates[i])).toList();
    }

    private static DailyRate row(String source, String rateDate, Instant fetchedAt) {
        return row(source, rateDate, fetchedAt, "1000");
    }

    private static DailyRate row(String source, String rateDate, Instant fetchedAt, String rate) {
        return new DailyRate(1, "XTS", source, null, 1, LocalDate.parse(rateDate), Instant.EPOCH,
                new BigDecimal(rate), null, null, "MOCK".equals(source), fetchedAt);
    }
}
