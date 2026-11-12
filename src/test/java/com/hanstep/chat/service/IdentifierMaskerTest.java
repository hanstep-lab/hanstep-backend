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
        assertThat(IdentifierMasker.mask("rent 520,000 KRW on 2026-11-10")).isEqualTo("rent 520,000 KRW on 2026-11-10");
        assertThat(IdentifierMasker.mask("12345678901234567")).isEqualTo("12345678901234567");
    }

    /** 누락 우선 결정으로 기대값이 바뀐 기존 단언. 전화번호는 범위 밖이라 가려져도 된다. */
    @Test
    void 누락_우선으로_10자리_이상_번호와_전화번호도_가린다() {
        // 전화번호(범위 밖): 3-4-4 하이픈 묶음 11자리라 계좌 형식과 같이 가려진다
        assertThat(IdentifierMasker.mask("tel 010-1234-5678")).isEqualTo("tel ***-****-****");
        // 12자리 연속 숫자: 필리핀 BDO 계좌 길이라 단어 없이도 가린다
        assertThat(IdentifierMasker.mask("123456789012")).isEqualTo("************");
        // 7-7 하이픈: 묶음 2~7자리·전체 14자리라 계좌 형식으로 가린다
        assertThat(IdentifierMasker.mask("1234567-1234567")).isEqualTo("*******-*******");
    }

    @Test
    void 등록번호는_공백으로_나뉘어도_가린다() {
        assertThat(IdentifierMasker.mask("등록번호 920617 5738214 확인")).isEqualTo("등록번호 ****** ******* 확인");
    }

    @Test
    void 하이픈으로_나뉜_계좌와_카드번호를_가린다() {
        assertThat(IdentifierMasker.mask("계좌 110-482-937165 로")).isEqualTo("계좌 ***-***-****** 로");
        assertThat(IdentifierMasker.mask("기업 487-2093-6158-02")).isEqualTo("기업 ***-****-****-**");
        assertThat(IdentifierMasker.mask("card 5391-7284-6035-1947")).isEqualTo("card ****-****-****-****");
        assertThat(IdentifierMasker.mask("amex 3742-816395-72048")).isEqualTo("amex ****-******-*****");
        assertThat(IdentifierMasker.mask("BPI 4207-3185-96 이에요")).isEqualTo("BPI ****-****-** 이에요");
    }

    @Test
    void 공백으로_나뉜_묶음은_3개_이상이면_가린다() {
        assertThat(IdentifierMasker.mask("my card 4926 3815 7042 6639 got"))
                .isEqualTo("my card **** **** **** **** got");
        assertThat(IdentifierMasker.mask("BDO 0062 4819 3705 로")).isEqualTo("BDO **** **** **** 로");
    }

    @Test
    void 단어_없는_10자리에서_12자리_연속_숫자도_가린다() {
        assertThat(IdentifierMasker.mask("엄마한테 2958174036 으로")).isEqualTo("엄마한테 ********** 으로");
        assertThat(IdentifierMasker.mask("보내줘 38251947603")).isEqualTo("보내줘 ***********");
    }

    @Test
    void 금액_날짜_시각_환율_점수는_그대로() {
        String[] keep = {
            "목표 자산 1000000000원 모으기",
            "salary 2350000 KRW",
            "₱20,080 은 대략 ₩500000 이에요",
            "출국 2026-11-12 14:30",
            "체류기간 2022-12-05 ~ 2027-10-04",
            "신청일 2026.11.12, 기준일 20261112",
            "E-7-4 점수 235점, 근무 60개월, 저축률 29.8%",
            "USD 환율 1406.51 기준, 1 PHP = 24.90 KRW",
            "보고서 202611 1234 참고하세요",
            "접수번호 20261112-0042",
        };
        for (String text : keep) {
            assertThat(IdentifierMasker.mask(text)).as(text).isEqualTo(text);
        }
    }

    @Test
    void 비었거나_null이면_그대로() {
        assertThat(IdentifierMasker.mask("")).isEmpty();
        assertThat(IdentifierMasker.mask(null)).isNull();
    }
}
