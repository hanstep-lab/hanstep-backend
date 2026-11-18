package com.hanstep.common.dto;

import java.math.BigDecimal;

/** 환율 등 소수 응답 표기. 끝자리 0 을 떼고, 정수가 되면 지수 표기(1.35E+3)가 되지 않게 scale 을 0 으로 맞춘다. */
public final class Decimals {

    private Decimals() {
    }

    public static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
