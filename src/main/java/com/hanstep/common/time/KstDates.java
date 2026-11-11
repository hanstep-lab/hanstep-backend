package com.hanstep.common.time;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** KST 기준 날짜 계산. 오늘 날짜는 호출하는 쪽에서 LocalDate.now(KST) 로 구해 넘긴다. */
public final class KstDates {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private KstDates() {
    }

    /** 온전한 달력 월의 시작. 1일이 아니면 다음 달 1일(CMT-03·FIN-05 수집 기간 자르기). */
    public static LocalDate firstFullMonthStart(LocalDate date) {
        return date.getDayOfMonth() == 1 ? date : date.plusMonths(1).withDayOfMonth(1);
    }

    /** 온전한 달력 월의 끝. 말일이 아니면 전달 말일(CMT-03·FIN-05 수집 기간 자르기). */
    public static LocalDate lastFullMonthEnd(LocalDate date) {
        return date.equals(date.with(TemporalAdjusters.lastDayOfMonth())) ? date : date.withDayOfMonth(1).minusDays(1);
    }

    /** today 부터 date 까지 꽉 찬 달 수(내림, 0 가능). date 가 없거나 today 이하이면 null. */
    public static Integer monthsUntil(LocalDate today, LocalDate date) {
        if (date == null || !date.isAfter(today)) {
            return null;
        }
        return Math.toIntExact(Period.between(today, date).toTotalMonths());
    }

    /** 목표일까지 매달 모아야 할 금액. 목표 금액 ÷ max(남은 달, 1), 원 단위 올림. 목표일이 없거나 지났으면 null. */
    public static Long monthlyRequired(long targetAmount, LocalDate today, LocalDate targetDate) {
        Integer months = monthsUntil(today, targetDate);
        if (months == null) {
            return null;
        }
        return BigDecimal.valueOf(targetAmount)
                .divide(BigDecimal.valueOf(Math.max(months, 1)), 0, RoundingMode.CEILING)
                .longValueExact();
    }
}
