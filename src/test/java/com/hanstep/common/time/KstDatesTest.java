package com.hanstep.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class KstDatesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    @Test
    void 만료일까지_꽉_찬_달로_나눈다() {
        LocalDate date = LocalDate.of(2027, 10, 4);

        assertThat(KstDates.monthsUntil(TODAY, date)).isEqualTo(10);
        assertThat(KstDates.monthlyRequired(6_000_000, TODAY, date)).isEqualTo(600_000L);
    }

    @Test
    void 나누어떨어지지_않으면_원_단위_올림() {
        assertThat(KstDates.monthlyRequired(1_000_000, TODAY, TODAY.plusMonths(3))).isEqualTo(333_334L);
    }

    @Test
    void 한_달_미만이면_1개월로_나눈다() {
        LocalDate date = TODAY.plusDays(20);

        assertThat(KstDates.monthsUntil(TODAY, date)).isZero();
        assertThat(KstDates.monthlyRequired(6_000_000, TODAY, date)).isEqualTo(6_000_000L);
    }

    @Test
    void 목표일이_오늘이거나_지났으면_null() {
        assertThat(KstDates.monthsUntil(TODAY, TODAY)).isNull();
        assertThat(KstDates.monthsUntil(TODAY, TODAY.minusDays(1))).isNull();
        assertThat(KstDates.monthlyRequired(6_000_000, TODAY, TODAY)).isNull();
        assertThat(KstDates.monthlyRequired(6_000_000, TODAY, TODAY.minusMonths(1))).isNull();
    }
}
