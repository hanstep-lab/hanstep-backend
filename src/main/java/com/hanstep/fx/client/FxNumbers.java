package com.hanstep.fx.client;

import java.math.BigDecimal;

/** 원천 숫자 검증. exchange_rates 환율 칸이 DECIMAL(18,4)라 소수 5자리 이상은 반올림되므로 저장하지 않는다(반올림 금지). */
final class FxNumbers {

    private static final int MAX_SCALE = 4;
    private static final int MAX_INTEGER_DIGITS = 14;

    private FxNumbers() {
    }

    /** 쉼표 있는 문자열("1,332.4")을 BigDecimal 로. 잘못되면 IllegalArgumentException(이유 문구). */
    static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("missing");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.strip().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number '" + raw + "'");
        }
        return check(value);
    }

    /** 0보다 크고 소수 4자리·정수부 14자리까지. 끝자리 0 은 자리 수에 세지 않는다. */
    static BigDecimal check(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("missing");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("must be greater than 0 (" + value.toPlainString() + ")");
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > MAX_SCALE) {
            throw new IllegalArgumentException("more than " + MAX_SCALE + " decimal places (" + value.toPlainString() + ")");
        }
        if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("too large (" + value.toPlainString() + ")");
        }
        return value;
    }
}
