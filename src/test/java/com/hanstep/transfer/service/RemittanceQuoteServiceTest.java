package com.hanstep.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RemittanceQuoteServiceTest {

    @Test
    void 환율은_끝자리_0을_떼고_정수가_돼도_지수_표기가_아니다() {
        assertThat(RemittanceQuoteService.plain(new BigDecimal("22.3200")).toString()).isEqualTo("22.32");
        assertThat(RemittanceQuoteService.plain(new BigDecimal("1350.00")).toString()).isEqualTo("1350");
        assertThat(RemittanceQuoteService.plain(new BigDecimal("1350.00")).scale()).isZero();
    }

    @Test
    void 계좌번호는_숫자_끝_4자리만() {
        assertThat(RemittanceQuoteService.last4("****5566")).isEqualTo("5566");
        assertThat(RemittanceQuoteService.last4("110-***-**4821")).isEqualTo("4821");
        assertThat(RemittanceQuoteService.last4("**12")).isEqualTo("12");
    }
}
