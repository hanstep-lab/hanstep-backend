package com.hanstep.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanstep.transfer.config.RemittanceProperties;
import com.hanstep.transfer.dto.RemittanceQuoteResponse;
import com.hanstep.transfer.dto.RemittanceQuoteResponse.ProductQuote;
import com.hanstep.transfer.dto.RemittanceQuoteResponse.QuoteSource;
import com.hanstep.transfer.service.RemittanceQuoteCalculator.Input;
import com.hanstep.transfer.service.RemittanceQuoteCalculator.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 송금 견적 계산(#121). 공시값은 application.yaml 그대로(빈 주입) 쓰고, 기대값은 손 계산표(Java 구현과 별개로 계산)와 비교한다.
 * - quote_cases_usd_targets.csv: 두 상품 × 송금 USD 20·520·5,000.00·5,000.01 × 과거 0·1·3·6·9회 = 40건(원화는 역산)
 * - quote_cases_v1.csv: 두 상품에 같은 원화(화면 입력)를 넣은 40건
 * USD 매매기준율 1331.50(신한 고시표와 같게 둬서 우대 없는 적용 환율이 1344.20), PHP 참고 20.984.
 */
@SpringBootTest
class RemittanceQuoteCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    private static final Rate USD = new Rate(new BigDecimal("1331.5000"), LocalDate.of(2026, 11, 17));
    private static final Rate PHP = new Rate(new BigDecimal("20.9840"), LocalDate.of(2026, 11, 17));

    @Autowired
    private RemittanceQuoteCalculator calculator;

    @Autowired
    private RemittanceProperties properties;

    @ParameterizedTest(name = "USD {0} 과거 {2}회 {3}")
    @CsvFileSource(resources = "/remittance/quote_cases_usd_targets.csv")
    void USD_지점_손_계산표_40건과_같다(ArgumentsAccessor row) {
        ProductQuote quote = assertRow(row, 1);
        if (quote.available()) {
            assertThat(quote.sendAmountUsd()).isEqualByComparingTo(row.getString(0));
        }
    }

    @ParameterizedTest(name = "{0}원 과거 {1}회 {2}")
    @CsvFileSource(resources = "/remittance/quote_cases_v1.csv")
    void 원화_입력_손_계산표_40건과_같다(ArgumentsAccessor row) {
        assertRow(row, 0);
    }

    /** offset 부터 amountKrw, pastRemittances, product, ... recipientAmountPhpRef 순(CSV 머리글). */
    private ProductQuote assertRow(ArgumentsAccessor row, int offset) {
        long amount = row.getLong(offset);
        int past = row.getInteger(offset + 1);
        ProductQuote quote = product(calculator.calculate(new Input(1, amount, USD, PHP, past, TODAY)),
                row.getString(offset + 2));

        assertThat(quote.available()).isEqualTo(row.getBoolean(offset + 3));
        assertThat(quote.unavailableReason()).isEqualTo(row.getString(offset + 4));
        assertThat(quote.preferentialPct()).isEqualByComparingTo(row.getString(offset + 5));
        assertThat(quote.remittanceCount1y()).isEqualTo(row.getInteger(offset + 6));
        assertThat(quote.usdAppliedRate()).isEqualByComparingTo(row.getString(offset + 7));
        assertDecimal(quote.sendAmountUsd(), row.getString(offset + 8));
        assertThat(quote.krwPrincipal()).isEqualTo(row.getLong(offset + 9));
        assertThat(quote.fxMarginKrw()).isEqualTo(row.getLong(offset + 10));
        assertThat(quote.sendFeeKrw()).isEqualTo(row.getLong(offset + 11));
        assertThat(quote.telegraphicFeeKrw()).isEqualTo(row.getLong(offset + 12));
        assertDecimal(quote.feeUsd(), row.getString(offset + 13));
        assertThat(quote.totalWithdrawKrw()).isEqualTo(row.getLong(offset + 14));
        assertThat(quote.unusedKrw()).isEqualTo(row.getLong(offset + 15));
        assertDecimal(quote.recipientAmountPhpRef(), row.getString(offset + 16));
        assertDecimal(quote.recipientAmountUsd(), row.getString(offset + 8));
        if (quote.available()) {
            assertThat(quote.totalWithdrawKrw()).isLessThanOrEqualTo(amount);
        }
        return quote;
    }

    @Test
    void 우대_구간은_이번_건을_포함한_횟수로_정한다() {
        assertThat(calculator.preferentialPct(0)).isEqualByComparingTo("0");
        assertThat(calculator.preferentialPct(1)).isEqualByComparingTo("50");
        assertThat(calculator.preferentialPct(2)).isEqualByComparingTo("50");
        assertThat(calculator.preferentialPct(3)).isEqualByComparingTo("60");
        assertThat(calculator.preferentialPct(5)).isEqualByComparingTo("60");
        assertThat(calculator.preferentialPct(6)).isEqualByComparingTo("65");
        assertThat(calculator.preferentialPct(8)).isEqualByComparingTo("65");
        assertThat(calculator.preferentialPct(9)).isEqualByComparingTo("70");
        assertThat(calculator.preferentialPct(23)).isEqualByComparingTo("70");
    }

    @Test
    void 기존_PHP_필드는_쏠편한_기준_참고값이다() {
        RemittanceQuoteResponse response = calculator.calculate(new Input(7, 700_000, USD, PHP, 0, TODAY));

        assertThat(response.recipientId()).isEqualTo(7);
        assertThat(response.sendAmount().amount()).isEqualTo(700_000);
        assertThat(response.sendAmount().currency()).isEqualTo("KRW");
        // 쏠편한 recipientAmountPhpRef 32963.25 를 1 PHP 내림
        assertThat(response.receiveAmount().amount()).isEqualTo(32_963);
        assertThat(response.receiveAmount().currency()).isEqualTo("PHP");
        assertThat(response.appliedRate().currency()).isEqualTo("PHP");
        assertThat(response.appliedRate().unit()).isEqualTo(1);
        assertThat(response.appliedRate().rate()).isEqualByComparingTo("20.984");
        assertThat(response.appliedRate().sourceLabel()).isEqualTo("ECB 기준환율 기반 참고값");
        assertThat(response.appliedRate().asOf()).isEqualTo(PHP.rateDate());
        assertThat(response.appliedRate().isMock()).isFalse();
        assertThat(response.isReference()).isTrue();
        assertThat(response.referenceNotice()).isEqualTo("LOCAL_PAYOUT_RATE_DIFFERS");
        assertThat(response.sendCurrency()).isEqualTo("USD");
        assertThat(response.defaultProduct()).isEqualTo("SOL_EASY");
        assertThat(response.isEstimated()).isTrue();
        assertThat(response.rates().usdBaseRate()).isEqualByComparingTo("1331.5");
        assertThat(response.rates().usdSource()).isEqualTo("KOREAEXIM");
        assertThat(response.rates().phpSource()).isEqualTo("frankfurter-ecb");
        assertThat(response.rates().usdPhpRefRate()).isEqualByComparingTo("63.4531");
        assertThat(response.quotes()).extracting(ProductQuote::product).containsExactly("SOL_EASY", "SOL_FAST");
    }

    @Test
    void 상품마다_안내와_값별_출처를_준다() {
        RemittanceQuoteResponse response = calculator.calculate(new Input(1, 700_000, USD, PHP, 0, TODAY));
        ProductQuote easy = product(response, "SOL_EASY");
        ProductQuote fast = product(response, "SOL_FAST");

        assertThat(easy.notices()).containsExactly("INTERMEDIARY_FEE_MAY_APPLY", "LOCAL_PAYOUT_RATE_DIFFERS",
                "FOREIGN_EXCHANGE_BANK_DESIGNATION_REQUIRED");
        assertThat(easy.unusedKrw()).isNull();
        assertThat(fast.unusedKrw()).isNull();
        assertThat(fast.notices()).containsExactly("LOCAL_PAYOUT_RATE_BY_AGENT", "LOCAL_PAYOUT_RATE_DIFFERS",
                "FOREIGN_EXCHANGE_BANK_DESIGNATION_REQUIRED");
        assertThat(easy.deliveryType()).isEqualTo("SWIFT_ACCOUNT");
        assertThat(fast.deliveryType()).isEqualTo("WESTERN_UNION");
        assertThat(easy.sources()).extracting(QuoteSource::item).containsExactly("usdBaseRate", "spreadPct",
                "preferentialPct", "remittanceCount1y", "sendFeeKrw", "telegraphicFeeKrw", "phpRefRate");
        assertThat(fast.sources()).extracting(QuoteSource::item).containsExactly("usdBaseRate", "spreadPct", "feeUsd",
                "perTransferLimitUsd", "phpRefRate");
        assertThat(source(easy, "sendFeeKrw").approvalNo()).contains("제2026-14794-1호");
        assertThat(source(fast, "feeUsd").approvalNo()).contains("제2026-12828-2호");
        assertThat(source(easy, "spreadPct").title()).contains("2026-11-18 09:07 11회차");
        assertThat(source(easy, "usdBaseRate").checkedOn()).isEqualTo(USD.rateDate());
        assertThat(source(easy, "remittanceCount1y").title()).contains("Mock");
        // "신한은행 환율"이라고 쓰지 않는다
        assertThat(response.toString()).doesNotContain("신한은행 환율");
    }

    @Test
    void 수수료를_빼면_0_이하면_두_상품_모두_AMOUNT_TOO_SMALL() {
        RemittanceQuoteResponse response = calculator.calculate(new Input(1, 4_700, USD, PHP, 0, TODAY));

        assertThat(response.quotes()).allSatisfy(q -> {
            assertThat(q.available()).isFalse();
            assertThat(q.unavailableReason()).isEqualTo("AMOUNT_TOO_SMALL");
            assertThat(q.sendAmountUsd()).isNull();
            assertThat(q.totalWithdrawKrw()).isNull();
        });
        assertThat(response.receiveAmount()).isNull();
        // 쏠빠른은 4,700 ÷ 1,344.20 = 3.49 − 3.5 = −0.01, 4,800 이면 0.07
        assertThat(product(calculator.calculate(new Input(1, 4_800, USD, PHP, 0, TODAY)), "SOL_FAST")
                .sendAmountUsd()).isEqualByComparingTo("0.07");
    }

    @Test
    void 쏠편한_수수료_경계_사이_금액은_USD_5000까지만_보낸다() {
        // 0원 수수료로는 5,004.29 USD(기준 초과), 10,000원 수수료로는 4,996.80 USD(기준 이하)가 되는 금액
        ProductQuote easy = product(calculator.calculate(new Input(1, 6_700_000, USD, PHP, 0, TODAY)), "SOL_EASY");

        assertThat(easy.sendAmountUsd()).isEqualByComparingTo("5000.00");
        assertThat(easy.sendFeeKrw()).isZero();
        assertThat(easy.krwPrincipal()).isEqualTo(6_689_250); // 5,000 × 1,337.85
        assertThat(easy.totalWithdrawKrw()).isEqualTo(6_694_250);
        // 남는 원화를 알리고 안내 코드를 붙인다
        assertThat(easy.unusedKrw()).isEqualTo(5_750);
        assertThat(easy.notices()).startsWith("AMOUNT_CAPPED_AT_FEE_BOUNDARY");
    }

    @Test
    void 쏠편한_이벤트가_끝나면_수수료를_모르므로_견적을_내지_않는다() {
        RemittanceProperties.SolEasy easy = properties.solEasy();
        RemittanceProperties ended = new RemittanceProperties(properties.usdSpreadPct(), properties.usdSpreadSource(),
                properties.phpReferenceNationalities(), properties.commonNotices(),
                new RemittanceProperties.SolEasy(false, easy.feeThresholdUsd(), easy.sendFeeAtOrBelowKrw(),
                        easy.sendFeeAboveKrw(), easy.telegraphicFeeKrw(), easy.preferentialTiers(),
                        easy.deliveryType(), easy.notices(), easy.source()),
                properties.solFast());

        RemittanceQuoteResponse response = new RemittanceQuoteCalculator(ended)
                .calculate(new Input(1, 700_000, USD, PHP, 0, TODAY));
        ProductQuote quote = product(response, "SOL_EASY");

        assertThat(quote.available()).isFalse();
        assertThat(quote.unavailableReason()).isEqualTo("FEE_TABLE_UNAVAILABLE");
        assertThat(quote.notices()).startsWith("FEE_TABLE_UNAVAILABLE");
        assertThat(quote.sendFeeKrw()).isNull();
        assertThat(quote.telegraphicFeeKrw()).isNull();
        assertThat(quote.totalWithdrawKrw()).isNull();
        assertThat(quote.usdAppliedRate()).isEqualByComparingTo("1337.85");
        assertThat(response.receiveAmount()).isNull();
        // 쏠빠른은 그대로 계산한다
        assertThat(product(response, "SOL_FAST").available()).isTrue();
    }

    @Test
    void PHP_참고_환율이_없으면_USD_견적만_내고_PHP_참고값은_null() {
        RemittanceQuoteResponse response = calculator.calculate(new Input(1, 700_000, USD, null, 0, TODAY));

        assertThat(response.receiveAmount()).isNull();
        assertThat(response.appliedRate()).isNull();
        assertThat(response.referenceNotice()).isEqualTo("PHP_REF_RATE_UNAVAILABLE");
        assertThat(response.rates().phpRefRate()).isNull();
        assertThat(response.rates().usdPhpRefRate()).isNull();
        assertThat(response.quotes()).allSatisfy(q -> {
            assertThat(q.available()).isTrue();
            assertThat(q.recipientAmountUsd()).isNotNull();
            assertThat(q.recipientAmountPhpRef()).isNull();
            assertThat(q.notices()).contains("PHP_REF_RATE_UNAVAILABLE").doesNotContain("LOCAL_PAYOUT_RATE_DIFFERS");
            assertThat(q.sources()).extracting(QuoteSource::item).doesNotContain("phpRefRate");
        });
        assertThat(product(response, "SOL_EASY").sendAmountUsd()).isEqualByComparingTo("519.49");
    }

    private static ProductQuote product(RemittanceQuoteResponse response, String product) {
        return response.quotes().stream().filter(q -> q.product().equals(product)).findFirst().orElseThrow();
    }

    private static QuoteSource source(ProductQuote quote, String item) {
        return quote.sources().stream().filter(s -> s.item().equals(item)).findFirst().orElseThrow();
    }

    private static void assertDecimal(BigDecimal actual, String expected) {
        if (expected == null) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isEqualByComparingTo(expected);
        }
    }
}
