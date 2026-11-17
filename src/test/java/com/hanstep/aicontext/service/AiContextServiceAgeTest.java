package com.hanstep.aicontext.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** INT-19 만 나이 계산. 생일 당일에 한 살 늘고, 2월 29일생은 평년에 3월 1일에 는다. */
class AiContextServiceAgeTest {

    @Test
    void 생일_전날과_당일() {
        LocalDate birth = LocalDate.of(1995, 8, 15);

        assertThat(AiContextService.ageOn(birth, LocalDate.of(2026, 8, 14))).isEqualTo(30);
        assertThat(AiContextService.ageOn(birth, LocalDate.of(2026, 8, 15))).isEqualTo(31);
    }

    @Test
    void 윤일생은_평년_3월_1일에_한_살_는다() {
        LocalDate birth = LocalDate.of(2000, 2, 29);

        assertThat(AiContextService.ageOn(birth, LocalDate.of(2026, 2, 28))).isEqualTo(25);
        assertThat(AiContextService.ageOn(birth, LocalDate.of(2026, 3, 1))).isEqualTo(26);
    }

    @Test
    void 생년월일이_없으면_null() {
        assertThat(AiContextService.ageOn(null, LocalDate.of(2026, 11, 17))).isNull();
    }
}
