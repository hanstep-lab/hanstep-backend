package com.hanstep.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** shortName 임시 규칙: 첫 단어, 첫 글자만 대문자. */
class UserMeResponseTest {

    @Test
    void 여러_단어면_첫_단어만_첫_글자_대문자로() {
        assertThat(UserMeResponse.shortNameOf("MARK ANTHONY REYES")).isEqualTo("Mark");
    }

    @Test
    void 공백이_여러_개여도_첫_단어를_쓴다() {
        assertThat(UserMeResponse.shortNameOf("  MARK   ANTHONY\tREYES ")).isEqualTo("Mark");
    }

    @Test
    void 한_단어면_그_단어() {
        assertThat(UserMeResponse.shortNameOf("REYES")).isEqualTo("Reyes");
        assertThat(UserMeResponse.shortNameOf("nguyen")).isEqualTo("Nguyen");
        assertThat(UserMeResponse.shortNameOf("M")).isEqualTo("M");
    }

    @Test
    void 비어_있으면_null() {
        assertThat(UserMeResponse.shortNameOf(null)).isNull();
        assertThat(UserMeResponse.shortNameOf("")).isNull();
        assertThat(UserMeResponse.shortNameOf("   ")).isNull();
    }
}
