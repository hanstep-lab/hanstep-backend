package com.hanstep.transfer.dto;

import com.hanstep.common.dto.Money;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 송금 견적(조회만, 송금 실행 없음, #121). 화면 TRF-02 와 챗봇 INT-18 이 같은 응답을 쓴다.
 * 송금 통화는 USD 이고 신한 쏠편한(기본)·쏠빠른 두 상품을 같이 계산한다. 모든 값은 추정이다.
 * receiveAmount·appliedRate 는 기존 이름과 PHP 기준 뜻을 그대로 둔 참고값(쏠편한 기준)이다.
 */
@Schema(description = "송금 견적(추정, 송금 실행 없음). 송금 통화 USD, 신한 쏠편한(기본)·쏠빠른 비교. "
        + "receiveAmount·appliedRate 는 PHP 참고값(쏠편한 기준)이며 실제 수령액과 다를 수 있음")
public record RemittanceQuoteResponse(
        @Schema(description = "받는 분 ID", example = "1")
        Long recipientId,

        @Schema(description = "요청한 원화 총액(내 계좌에서 나갈 돈, 수수료 포함). 요청 amount 그대로",
                example = "{\"amount\": 700000, \"currency\": \"KRW\"}")
        Money sendAmount,

        @Schema(description = "참고값, 실제 수령액과 다를 수 있음. 쏠편한으로 받는 USD 를 PHP 참고 환율로 바꾼 값(1 PHP 내림). "
                + "실제로는 필리핀 은행·취급점 환율로 바뀐다. PHP 참고 환율이 없거나 쏠편한 견적이 없으면 null",
                example = "{\"amount\": 33025, \"currency\": \"PHP\"}", nullable = true)
        Money receiveAmount,

        @Schema(description = "참고값, 실제 수령액과 다를 수 있음. PHP 참고 환율(ECB, 1 PHP 당 원화). 송금에 적용하는 환율이 아님"
                + "(송금 환율은 quotes[].usdAppliedRate). PHP 참고 환율이 없으면 null", nullable = true)
        AppliedRate appliedRate,

        @Schema(description = "receiveAmount·appliedRate 가 참고값인지. 항상 true", example = "true")
        boolean isReference,

        @Schema(description = "참고값 안내 코드. LOCAL_PAYOUT_RATE_DIFFERS(현지 환율로 바뀜), "
                + "PHP_REF_RATE_UNAVAILABLE(PHP 참고 환율 없음)", example = "LOCAL_PAYOUT_RATE_DIFFERS")
        String referenceNotice,

        @Schema(description = "송금 통화. 항상 USD(신한 SOL 해외송금에서 필리핀은 USD 송금)", example = "USD")
        String sendCurrency,

        @Schema(description = "기본 상품. receiveAmount 가 이 상품 기준", example = "SOL_EASY")
        String defaultProduct,

        @Schema(description = "추정값인지. 항상 true(공시값으로 계산한 값이며 실제 거래 조건과 다를 수 있음)", example = "true")
        boolean isEstimated,

        QuoteRates rates,

        @ArraySchema(arraySchema = @Schema(description = "상품별 견적. SOL_EASY(쏠편한, 기본), SOL_FAST(쏠빠른, 비교) 순"))
        List<ProductQuote> quotes
) {

    @Schema(name = "RemittanceQuoteRates", description = "견적에 쓴 환율. 원화 값은 1 외화 당 원")
    public record QuoteRates(
            @Schema(description = "USD 매매기준율(수출입은행 고시 실데이터)", example = "1338.8")
            BigDecimal usdBaseRate,

            @Schema(description = "USD 매매기준율 기준일", example = "2026-11-18")
            LocalDate usdRateDate,

            @Schema(description = "USD 출처", example = "KOREAEXIM")
            String usdSource,

            @Schema(description = "PHP 참고 환율(ECB 기준환율, 1 PHP 당 원화). 없으면 null", example = "20.984", nullable = true)
            BigDecimal phpRefRate,

            @Schema(description = "PHP 참고 환율 기준일(ECB 기준일). 없으면 null", example = "2026-11-17", nullable = true)
            LocalDate phpRateDate,

            @Schema(description = "PHP 출처. 없으면 null", example = "frankfurter-ecb", nullable = true)
            String phpSource,

            @Schema(description = "USD/PHP 참고 환율 = usdBaseRate ÷ phpRefRate(소수 4자리 반올림). 없으면 null",
                    example = "63.801", nullable = true)
            BigDecimal usdPhpRefRate
    ) {
    }

    @Schema(name = "RemittanceProductQuote", description = "상품 1개의 견적(추정). available=false 면 금액 칸은 null")
    public record ProductQuote(
            @Schema(description = "SOL_EASY(신한 쏠편한 해외송금) / SOL_FAST(신한 쏠빠른 해외송금)", example = "SOL_EASY")
            String product,

            @Schema(description = "이 금액으로 보낼 수 있는지", example = "true")
            boolean available,

            @Schema(description = "못 보내는 이유. AMOUNT_TOO_SMALL(수수료를 빼면 0 이하), LIMIT_EXCEEDED(쏠빠른 건당 "
                    + "USD 5,000 초과), FEE_TABLE_UNAVAILABLE(쏠편한 수수료 이벤트 종료, 수수료표 없음). 보낼 수 있으면 null",
                    nullable = true)
            String unavailableReason,

            @Schema(description = "송금 USD(0.01 내림)", example = "517.64", nullable = true)
            BigDecimal sendAmountUsd,

            @Schema(description = "USD 송금 스프레드(%)", example = "0.954")
            BigDecimal spreadPct,

            @Schema(description = "환율 우대율(%). 쏠편한은 1년 송금 횟수 구간(1~2회 50, 3~5회 60, 6~8회 65, 9회 이상 70), "
                    + "쏠빠른은 0", example = "70")
            BigDecimal preferentialPct,

            @Schema(description = "우대 구간을 정한 1년 송금 횟수(기준일 포함 과거 365일 + 이번 1건). 쏠빠른은 null",
                    example = "23", nullable = true)
            Integer remittanceCount1y,

            @Schema(description = "적용 송금 환율(1 USD 당 원화, 소수 2자리 반올림) = 매매기준율 × (1 + 스프레드 × (1 − 우대율))",
                    example = "1342.63")
            BigDecimal usdAppliedRate,

            @Schema(description = "환전에 쓰는 원화. 쏠편한 = amount − 송금수수료 − 전신료(0.01 USD 아래로 버린 끝전 포함, "
                    + "수수료 기준에 걸려 USD 5,000.00 까지만 보낼 때는 송금 USD × 적용 환율 원 단위 올림), "
                    + "쏠빠른 = 송금 USD × 적용 환율(원 단위 올림)", example = "695000", nullable = true)
            Long krwPrincipal,

            @Schema(description = "환전 마진(원) = 송금 USD × (적용 환율 − 매매기준율), 원 단위 반올림. "
                    + "krwPrincipal 에 이미 포함된 참고값이라 합산하지 않는다(totalWithdrawKrw 에 더하지 않음)",
                    example = "1983",
                    nullable = true)
            Long fxMarginKrw,

            @Schema(description = "송금수수료(원). 쏠편한만(USD 5,000 이하 0원, 넘으면 10,000원). 쏠빠른은 null",
                    example = "0", nullable = true)
            Long sendFeeKrw,

            @Schema(description = "전신료(원). 쏠편한만(5,000원). 쏠빠른은 null", example = "5000", nullable = true)
            Long telegraphicFeeKrw,

            @Schema(description = "송금수수료(USD). 쏠빠른만(3.5, 송금액에 더해 같은 환율로 출금). 쏠편한은 null",
                    nullable = true)
            BigDecimal feeUsd,

            @Schema(description = "출금 총액(원) = 요청 amount(#176). 0.01 USD 아래로 버린 끝전으로 줄이지 않는다. "
                    + "예외: 쏠편한이 수수료 기준(USD 5,000)에 걸려 USD 5,000.00 까지만 보낼 때는 원화 송금액 + 송금수수료 + 전신료"
                    + "(남는 원화는 unusedKrw)",
                    example = "700000", nullable = true)
            Long totalWithdrawKrw,

            @Schema(description = "쓰지 않고 남는 원화(요청 amount − totalWithdrawKrw). 쏠편한이 수수료 기준(USD 5,000)에 걸려 "
                    + "USD 5,000.00 까지만 보낼 때만 값이 있고 notices 에 AMOUNT_CAPPED_AT_FEE_BOUNDARY 가 붙는다. "
                    + "그 밖에는 null", example = "5750", nullable = true)
            Long unusedKrw,

            @Schema(description = "받는 분이 받는 USD(중계·수취은행 수수료 차감 전)", example = "517.64", nullable = true)
            BigDecimal recipientAmountUsd,

            @Schema(description = "참고값. 받는 USD × USD/PHP 참고 환율(0.01 반올림). 실제로는 현지 환율로 바뀐다. "
                    + "PHP 참고 환율이 없으면 null", example = "33025.95", nullable = true)
            BigDecimal recipientAmountPhpRef,

            @Schema(description = "받는 방법. SWIFT_ACCOUNT(해외 계좌 입금) / WESTERN_UNION(캐시픽업 수분 또는 계좌 1~2영업일)",
                    example = "SWIFT_ACCOUNT")
            String deliveryType,

            @ArraySchema(arraySchema = @Schema(description = "안내 코드(금액 없이 문구로 안내). INTERMEDIARY_FEE_MAY_APPLY, "
                    + "LOCAL_PAYOUT_RATE_BY_AGENT, LOCAL_PAYOUT_RATE_DIFFERS, PHP_REF_RATE_UNAVAILABLE, "
                    + "FEE_TABLE_UNAVAILABLE, AMOUNT_CAPPED_AT_FEE_BOUNDARY(쏠편한 USD 5,000.00 까지만, unusedKrw 참고), "
                    + "FOREIGN_EXCHANGE_BANK_DESIGNATION_REQUIRED"))
            List<String> notices,

            @ArraySchema(arraySchema = @Schema(description = "값마다의 출처"))
            List<QuoteSource> sources
    ) {
    }

    @Schema(name = "RemittanceQuoteSource", description = "값 출처")
    public record QuoteSource(
            @Schema(description = "어느 값의 출처인지(응답 필드 이름)", example = "sendFeeKrw")
            String item,

            @Schema(description = "출처 문서·데이터", example = "신한 SOL 쏠편한 해외송금 안내")
            String title,

            @Schema(description = "준법감시인 사전심사필 번호. 없으면 null",
                    example = "준법감시인 사전심사필 제2026-14794-1호(2026.09.16~2027.09.15)", nullable = true)
            String approvalNo,

            @Schema(description = "확인일(환율은 기준일)", example = "2026-11-18")
            LocalDate checkedOn
    ) {
    }
}
