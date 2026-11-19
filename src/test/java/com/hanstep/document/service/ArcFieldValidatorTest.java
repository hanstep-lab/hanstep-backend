package com.hanstep.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.common.time.KstDates;
import com.hanstep.document.domain.ArcFieldKey;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 체류 만료일 검사(#145). 날짜는 오늘(KST) 기준으로 계산한다. stayExpiry 는 DB 를 쓰지 않아 JdbcTemplate 없이 만든다. */
class ArcFieldValidatorTest {

    private final ArcFieldValidator validator = new ArcFieldValidator(null);

    @Test
    void 체류_만료일이_어제면_거절() {
        String yesterday = LocalDate.now(KstDates.KST).minusDays(1).toString();

        assertThat(validator.invalidReason(ArcFieldKey.STAY_EXPIRY, yesterday)).isEqualTo("must not be in the past");
    }

    @Test
    void 체류_만료일이_오늘이나_내일이면_허용() {
        LocalDate today = LocalDate.now(KstDates.KST);

        assertThat(validator.invalidReason(ArcFieldKey.STAY_EXPIRY, today.toString())).isNull();
        assertThat(validator.invalidReason(ArcFieldKey.STAY_EXPIRY, today.plusDays(1).toString())).isNull();
    }

    @Test
    void 체류_만료일_형식이_틀리면_형식_오류() {
        assertThat(validator.invalidReason(ArcFieldKey.STAY_EXPIRY, "03/01/2027")).isEqualTo("must be YYYY-MM-DD");
    }
}
