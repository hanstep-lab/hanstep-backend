package com.hanstep.common.time;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

/** KST 기준 날짜 계산. 오늘 날짜는 호출하는 쪽에서 LocalDate.now(KST) 로 구해 넘긴다. */
public final class KstDates {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private KstDates() {
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
