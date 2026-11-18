package com.hanstep.transfer.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml 의 hanstep.remittance.* 값. 신한 SOL 해외송금 공시값(#121). 코드 상수로 두지 않는다.
 * 값이 바뀌면 yaml 과 출처(title·approvalNo·checkedOn)를 함께 고친다.
 *
 * @param usdSpreadPct             신한 USD 송금 스프레드(%). 적용 송금 환율 = 매매기준율 × (1 + 스프레드 × (1 − 우대율))
 * @param usdSpreadSource          스프레드 출처(국제환율 고시표 회차)
 * @param phpReferenceNationalities PHP 참고 환율을 붙일 국적(ISO 3166-1 alpha-3). 그 밖은 PHP 참고값 없음
 * @param commonNotices            모든 상품에 붙는 안내 코드
 * @param solEasy                  신한 쏠편한 해외송금(기본 상품)
 * @param solFast                  신한 쏠빠른 해외송금(비교 상품)
 */
@ConfigurationProperties(prefix = "hanstep.remittance")
public record RemittanceProperties(BigDecimal usdSpreadPct, Source usdSpreadSource,
                                   List<String> phpReferenceNationalities, List<String> commonNotices,
                                   SolEasy solEasy, SolFast solFast) {

    /** 공시 출처. approvalNo 는 준법감시인 사전심사필 번호(없으면 null). */
    public record Source(String title, String approvalNo, LocalDate checkedOn) {
    }

    /**
     * @param eventActive          송금수수료·전신료 이벤트 조건이 유효한지. false 면 수수료를 알 수 없어 견적을 내지 않는다
     * @param feeThresholdUsd      이 USD 이하면 sendFeeAtOrBelowKrw, 넘으면 sendFeeAboveKrw
     * @param telegraphicFeeKrw    전신료(원화로 별도 출금)
     * @param preferentialTiers    환율 우대 구간(이번 건 포함 1년 송금 횟수가 minCount 이상이면 pct)
     */
    public record SolEasy(boolean eventActive, BigDecimal feeThresholdUsd, long sendFeeAtOrBelowKrw,
                          long sendFeeAboveKrw, long telegraphicFeeKrw, List<Tier> preferentialTiers,
                          String deliveryType, List<String> notices, Source source) {
    }

    public record Tier(int minCount, BigDecimal pct) {
    }

    /**
     * @param feeUsd              송금수수료(USD). 송금액에 더해 같은 환율로 출금
     * @param perTransferLimitUsd 건당 한도. 넘으면 LIMIT_EXCEEDED
     */
    public record SolFast(BigDecimal feeUsd, BigDecimal perTransferLimitUsd, String deliveryType,
                          List<String> notices, Source source) {
    }
}
