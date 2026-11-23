package com.hanstep.transfer.service;

import com.hanstep.common.dto.Money;
import com.hanstep.fx.repository.ExchangeRateReader;
import com.hanstep.transfer.config.RemittanceProperties;
import com.hanstep.transfer.config.RemittanceProperties.SolEasy;
import com.hanstep.transfer.config.RemittanceProperties.SolFast;
import com.hanstep.transfer.config.RemittanceProperties.Source;
import com.hanstep.transfer.dto.AppliedRate;
import com.hanstep.transfer.dto.RemittanceQuoteResponse;
import com.hanstep.transfer.dto.RemittanceQuoteResponse.ProductQuote;
import com.hanstep.transfer.dto.RemittanceQuoteResponse.QuoteRates;
import com.hanstep.transfer.dto.RemittanceQuoteResponse.QuoteSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 송금 견적 계산(#121). DB 를 읽지 않는다. 화면(TRF-02)과 챗봇(INT-18)이 이 계산 하나를 같이 쓴다.
 *
 * <p>입력 amount 는 내 계좌에서 나갈 원화 총액(수수료 포함). 송금 통화는 USD 이고 상품 두 개를 같이 계산한다.
 * <ul>
 *   <li>적용 송금 환율 = USD 매매기준율 × (1 + 스프레드 × (1 − 우대율)), 소수 2자리 반올림</li>
 *   <li>SOL_EASY: 송금 USD = (amount − 송금수수료 − 전신료) ÷ 적용 환율. 송금수수료는 송금 USD 로 정해진다(아래 feeFor)</li>
 *   <li>SOL_FAST: 송금 USD = amount ÷ 적용 환율 − 수수료 USD(우대 없음)</li>
 *   <li>송금 USD 는 0.01 내림, 원화 송금액·출금 총액은 원 단위 올림(보낼 원화 올림, 기존 TRF-02 관례)이라 출금 총액은
 *       amount 를 넘지 않는다. 환전 마진은 비용 설명이라 원 단위 반올림</li>
 *   <li>PHP 는 참고값만: USD/PHP = USD 매매기준율 ÷ PHP 참고 환율(소수 4자리 반올림), 수령 PHP = 받는 USD × USD/PHP(0.01 반올림)</li>
 * </ul>
 * 중계·수취은행 수수료와 현지 환전은 금액을 만들지 않고 notice 로만 안내한다.
 */
@Component
public class RemittanceQuoteCalculator {

    public static final String SOL_EASY = "SOL_EASY";
    public static final String SOL_FAST = "SOL_FAST";
    public static final String SEND_CURRENCY = "USD";
    public static final String PHP = "PHP";
    static final String USD_SOURCE = "KOREAEXIM";
    static final String PHP_SOURCE = "frankfurter-ecb";

    static final String AMOUNT_TOO_SMALL = "AMOUNT_TOO_SMALL";
    static final String LIMIT_EXCEEDED = "LIMIT_EXCEEDED";
    static final String FEE_TABLE_UNAVAILABLE = "FEE_TABLE_UNAVAILABLE";
    static final String LOCAL_PAYOUT_RATE_DIFFERS = "LOCAL_PAYOUT_RATE_DIFFERS";
    static final String PHP_REF_RATE_UNAVAILABLE = "PHP_REF_RATE_UNAVAILABLE";
    static final String AMOUNT_CAPPED_AT_FEE_BOUNDARY = "AMOUNT_CAPPED_AT_FEE_BOUNDARY";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Source USD_RATE_SOURCE_TITLE = new Source("한국수출입은행 현재환율 Open API 매매기준율(USD)",
            null, null);
    private static final Source PHP_RATE_SOURCE_TITLE = new Source("Frankfurter v2 ECB 기준환율(PHP)", null, null);
    private static final Source COUNT_SOURCE_TITLE = new Source("마이데이터 거래내역 해외송금 건수(Mock)", null, null);

    private final RemittanceProperties properties;

    public RemittanceQuoteCalculator(RemittanceProperties properties) {
        this.properties = properties;
    }

    /**
     * 계산 입력. php 가 null 이면 PHP 참고값 없이(USD 견적은 그대로) 낸다. pastRemittances 는 이번 건을 뺀 1년 건수.
     * english 면 참고 환율 출처 문구(appliedRate.sourceLabel)를 영어로 낸다.
     */
    public record Input(long recipientId, long amountKrw, Rate usd, Rate php, int pastRemittances, LocalDate asOf,
                        boolean english) {

        /** 한국어 문구. */
        public Input(long recipientId, long amountKrw, Rate usd, Rate php, int pastRemittances, LocalDate asOf) {
            this(recipientId, amountKrw, usd, php, pastRemittances, asOf, false);
        }
    }

    /** 환율 1개(원/1 외화)와 기준일, 출처 이름(KOREAEXIM·frankfurter-ecb·MOCK), Mock 여부. */
    public record Rate(BigDecimal rate, LocalDate rateDate, String source, boolean mock) {

        /** 테스트용: 출처는 운영 기본값(USD KOREAEXIM, PHP frankfurter-ecb), 실데이터. */
        public Rate(BigDecimal rate, LocalDate rateDate) {
            this(rate, rateDate, null, false);
        }
    }

    public RemittanceQuoteResponse calculate(Input input) {
        BigDecimal base = input.usd().rate();
        BigDecimal usdPhp = input.php() == null ? null
                : base.divide(input.php().rate(), 4, RoundingMode.HALF_UP);
        ProductQuote easy = solEasy(input, usdPhp);
        ProductQuote fast = solFast(input, usdPhp);

        Money receive = easy.recipientAmountPhpRef() == null ? null
                : new Money(easy.recipientAmountPhpRef().setScale(0, RoundingMode.DOWN).longValueExact(), PHP);
        AppliedRate phpRate = input.php() == null ? null
                : new AppliedRate(PHP, 1, RemittanceQuoteService.plain(input.php().rate()),
                input.english() ? ExchangeRateReader.MARKET_REF_LABEL_EN : ExchangeRateReader.MARKET_REF_LABEL,
                input.php().rateDate(), input.php().mock());
        QuoteRates rates = new QuoteRates(RemittanceQuoteService.plain(base), input.usd().rateDate(),
                sourceOr(input.usd(), USD_SOURCE),
                input.php() == null ? null : RemittanceQuoteService.plain(input.php().rate()),
                input.php() == null ? null : input.php().rateDate(),
                input.php() == null ? null : sourceOr(input.php(), PHP_SOURCE), usdPhp);
        return new RemittanceQuoteResponse(input.recipientId(), Money.krw(input.amountKrw()), receive, phpRate, true,
                input.php() == null ? PHP_REF_RATE_UNAVAILABLE : LOCAL_PAYOUT_RATE_DIFFERS, SEND_CURRENCY, SOL_EASY,
                true, rates, List.of(easy, fast));
    }

    private static String sourceOr(Rate rate, String fallback) {
        return rate.source() != null ? rate.source() : fallback;
    }

    /** 우대율(%). 이번 건 포함 건수가 minCount 이상인 가장 높은 구간. 없으면 0. */
    BigDecimal preferentialPct(int countWithThis) {
        BigDecimal pct = BigDecimal.ZERO;
        for (RemittanceProperties.Tier tier : properties.solEasy().preferentialTiers()) {
            if (countWithThis >= tier.minCount() && tier.pct().compareTo(pct) > 0) {
                pct = tier.pct();
            }
        }
        return pct;
    }

    /** 적용 송금 환율 = 매매기준율 × (1 + 스프레드 × (1 − 우대율)), 소수 2자리 반올림. */
    BigDecimal appliedRate(BigDecimal base, BigDecimal preferentialPct) {
        BigDecimal spread = properties.usdSpreadPct().divide(HUNDRED);
        BigDecimal keep = BigDecimal.ONE.subtract(preferentialPct.divide(HUNDRED));
        return base.multiply(BigDecimal.ONE.add(spread.multiply(keep))).setScale(2, RoundingMode.HALF_UP);
    }

    private ProductQuote solEasy(Input input, BigDecimal usdPhp) {
        SolEasy easy = properties.solEasy();
        int count = input.pastRemittances() + 1;
        BigDecimal pref = preferentialPct(count);
        BigDecimal rate = appliedRate(input.usd().rate(), pref);
        List<String> notices = notices(easy.notices(), input.php() != null);
        List<QuoteSource> sources = new ArrayList<>(commonSources(input));
        sources.add(source("preferentialPct", easy.source()));
        sources.add(source("remittanceCount1y", COUNT_SOURCE_TITLE, input.asOf()));
        sources.add(source("sendFeeKrw", easy.source()));
        sources.add(source("telegraphicFeeKrw", easy.source()));
        phpSource(input, sources);
        if (!easy.eventActive()) {
            notices.addFirst(FEE_TABLE_UNAVAILABLE);
            return unavailable(SOL_EASY, FEE_TABLE_UNAVAILABLE, pref, count, rate, easy.deliveryType(), notices,
                    sources);
        }

        BigDecimal amount = BigDecimal.valueOf(input.amountKrw());
        long tel = easy.telegraphicFeeKrw();
        BigDecimal usd = floorUsd(amount.subtract(BigDecimal.valueOf(easy.sendFeeAtOrBelowKrw() + tel)), rate);
        long fee = easy.sendFeeAtOrBelowKrw();
        boolean capped = false;
        if (usd.signum() > 0 && usd.compareTo(easy.feeThresholdUsd()) > 0) {
            BigDecimal higher = floorUsd(amount.subtract(BigDecimal.valueOf(easy.sendFeeAboveKrw() + tel)), rate);
            if (higher.compareTo(easy.feeThresholdUsd()) > 0) {
                usd = higher;
                fee = easy.sendFeeAboveKrw();
            } else {
                // 낮은 수수료로는 기준을 넘고 높은 수수료로는 기준 이하가 되는 구간: 기준 금액까지만 보낸다(낮은 수수료).
                // 남는 원화는 unusedKrw 로 알리고 AMOUNT_CAPPED_AT_FEE_BOUNDARY 를 붙인다
                usd = easy.feeThresholdUsd().setScale(2);
                capped = true;
            }
        }
        if (usd.signum() <= 0) {
            return unavailable(SOL_EASY, AMOUNT_TOO_SMALL, pref, count, rate, easy.deliveryType(), notices, sources);
        }
        long principal = krw(usd.multiply(rate));
        long total = principal + fee + tel;
        if (capped) {
            notices.addFirst(AMOUNT_CAPPED_AT_FEE_BOUNDARY);
        }
        return new ProductQuote(SOL_EASY, true, null, usd, spreadPct(), pref, count, rate, principal,
                margin(usd, rate, input.usd().rate()), fee, tel, null, total,
                capped ? input.amountKrw() - total : null, usd, phpRef(usd, usdPhp), easy.deliveryType(), notices,
                sources);
    }

    private ProductQuote solFast(Input input, BigDecimal usdPhp) {
        SolFast fast = properties.solFast();
        BigDecimal rate = appliedRate(input.usd().rate(), BigDecimal.ZERO);
        List<String> notices = notices(fast.notices(), input.php() != null);
        List<QuoteSource> sources = new ArrayList<>(commonSources(input));
        sources.add(source("feeUsd", fast.source()));
        sources.add(source("perTransferLimitUsd", fast.source()));
        phpSource(input, sources);

        BigDecimal usd = floorUsd(BigDecimal.valueOf(input.amountKrw()), rate).subtract(fast.feeUsd());
        if (usd.signum() <= 0) {
            return unavailable(SOL_FAST, AMOUNT_TOO_SMALL, BigDecimal.ZERO, null, rate, fast.deliveryType(), notices,
                    sources);
        }
        if (usd.compareTo(fast.perTransferLimitUsd()) > 0) {
            return unavailable(SOL_FAST, LIMIT_EXCEEDED, BigDecimal.ZERO, null, rate, fast.deliveryType(), notices,
                    sources);
        }
        return new ProductQuote(SOL_FAST, true, null, usd, spreadPct(), BigDecimal.ZERO, null, rate,
                krw(usd.multiply(rate)), margin(usd, rate, input.usd().rate()), null, null,
                RemittanceQuoteService.plain(fast.feeUsd()), krw(usd.add(fast.feeUsd()).multiply(rate)), null, usd,
                phpRef(usd, usdPhp), fast.deliveryType(), notices, sources);
    }

    private ProductQuote unavailable(String product, String reason, BigDecimal pref, Integer count, BigDecimal rate,
                                     String deliveryType, List<String> notices, List<QuoteSource> sources) {
        return new ProductQuote(product, false, reason, null, spreadPct(), pref, count, rate, null, null, null, null,
                null, null, null, null, null, deliveryType, notices, sources);
    }

    /** 상품 안내 + PHP 참고값 안내 + 공통 안내. */
    private List<String> notices(List<String> product, boolean hasPhp) {
        List<String> notices = new ArrayList<>(product);
        notices.add(hasPhp ? LOCAL_PAYOUT_RATE_DIFFERS : PHP_REF_RATE_UNAVAILABLE);
        notices.addAll(properties.commonNotices());
        return notices;
    }

    private List<QuoteSource> commonSources(Input input) {
        return List.of(source("usdBaseRate", USD_RATE_SOURCE_TITLE, input.usd().rateDate()),
                source("spreadPct", properties.usdSpreadSource()));
    }

    private static void phpSource(Input input, List<QuoteSource> sources) {
        if (input.php() != null) {
            sources.add(source("phpRefRate", PHP_RATE_SOURCE_TITLE, input.php().rateDate()));
        }
    }

    private static QuoteSource source(String item, Source source) {
        return new QuoteSource(item, source.title(), source.approvalNo(), source.checkedOn());
    }

    private static QuoteSource source(String item, Source title, LocalDate checkedOn) {
        return new QuoteSource(item, title.title(), title.approvalNo(), checkedOn);
    }

    private BigDecimal spreadPct() {
        return RemittanceQuoteService.plain(properties.usdSpreadPct());
    }

    private static BigDecimal phpRef(BigDecimal usd, BigDecimal usdPhp) {
        return usdPhp == null ? null : usd.multiply(usdPhp).setScale(2, RoundingMode.HALF_UP);
    }

    /** 원화 ÷ 적용 환율을 0.01 USD 내림. */
    private static BigDecimal floorUsd(BigDecimal krw, BigDecimal rate) {
        return krw.divide(rate, 2, RoundingMode.DOWN);
    }

    /** 환전 마진(원) = 송금 USD × (적용 환율 − 매매기준율), 원 단위 반올림(내는 돈이 아니라 비용 설명이라 올림하지 않음). */
    private static long margin(BigDecimal usd, BigDecimal rate, BigDecimal base) {
        return usd.multiply(rate.subtract(base)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 원 단위 올림(내 계좌에서 나가는 돈). */
    private static long krw(BigDecimal value) {
        return value.setScale(0, RoundingMode.CEILING).longValueExact();
    }
}
