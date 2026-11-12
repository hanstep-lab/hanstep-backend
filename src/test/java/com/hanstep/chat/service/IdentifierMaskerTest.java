package com.hanstep.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentifierMaskerTest {

    @Test
    void 외국인등록번호_형식은_하이픈을_남기고_숫자만_가린다() {
        assertThat(IdentifierMasker.mask("my ARC is 900101-5234567 ok"))
                .isEqualTo("my ARC is ******-******* ok");
    }

    @Test
    void 하이픈_없는_13자리와_16자리까지_연속_숫자를_가린다() {
        assertThat(IdentifierMasker.mask("id 9001015234567")).isEqualTo("id *************");
        assertThat(IdentifierMasker.mask("card 1234567812345678.")).isEqualTo("card ****************.");
        assertThat(IdentifierMasker.mask("acct:11012345678901")).isEqualTo("acct:**************");
    }

    @Test
    void 짧은_숫자_금액_17자리_이상은_그대로() {
        assertThat(IdentifierMasker.mask("rent 520,000 KRW on 2026-11-10, tel 010-1234-5678"))
                .isEqualTo("rent 520,000 KRW on 2026-11-10, tel 010-1234-5678");
        assertThat(IdentifierMasker.mask("123456789012")).isEqualTo("123456789012");
        assertThat(IdentifierMasker.mask("12345678901234567")).isEqualTo("12345678901234567");
        assertThat(IdentifierMasker.mask("1234567-1234567")).isEqualTo("1234567-1234567");
    }

    @Test
    void 비었거나_null이면_그대로() {
        assertThat(IdentifierMasker.mask("")).isEmpty();
        assertThat(IdentifierMasker.mask(null)).isNull();
    }
}
