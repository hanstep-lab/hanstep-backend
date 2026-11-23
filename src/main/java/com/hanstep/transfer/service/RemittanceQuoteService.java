package com.hanstep.transfer.service;

import com.hanstep.common.dto.Decimals;
import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.fx.repository.ExchangeRateReader;
import com.hanstep.transfer.config.RemittanceProperties;
import com.hanstep.transfer.dto.AppliedRate;
import com.hanstep.transfer.dto.RemittanceQuoteResponse;
import com.hanstep.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 해외송금 받는 분·참고 환율·견적(조회만, 송금 실행 없음). 화면 API(TRF)와 챗봇 내부 API 가 같이 쓴다.
 * 잔액 확인은 화면 API 에서만 한다(내부 API 는 잔액을 다루지 않음).
 */
@Service
@Transactional(readOnly = true)
public class RemittanceQuoteService {

    private final JdbcTemplate jdbcTemplate;
    private final RemittanceQuoteCalculator calculator;
    private final RemittanceProperties properties;
    private final ExchangeRateReader rateReader;
    private final UserRepository userRepository;

    public RemittanceQuoteService(JdbcTemplate jdbcTemplate, RemittanceQuoteCalculator calculator,
                                  RemittanceProperties properties, ExchangeRateReader rateReader,
                                  UserRepository userRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.calculator = calculator;
        this.properties = properties;
        this.rateReader = rateReader;
        this.userRepository = userRepository;
    }

    /** 관계를 확인한 본인의 활성 받는 분. MOTHER 먼저, 그다음 id 순. */
    public List<RemittanceRecipient> recipients(long userId) {
        return jdbcTemplate.query("""
                        SELECT id, name_en, relationship, country_code, currency, bank_name, account_no_masked, is_mock
                        FROM remittance_recipients
                        WHERE user_id = ? AND is_active AND relationship_confirmed_at IS NOT NULL
                        ORDER BY relationship = 'MOTHER' DESC, id""",
                (rs, i) -> new RemittanceRecipient(rs.getLong("id"), rs.getString("name_en"),
                        rs.getString("relationship"), rs.getString("country_code"), rs.getString("currency"),
                        rs.getString("bank_name"), last4(rs.getString("account_no_masked")), rs.getBoolean("is_mock")),
                userId);
    }

    /** 본인의 저장된 받는 분이 아니면 404 RECIPIENT_NOT_FOUND. */
    public RemittanceRecipient recipient(long userId, long recipientId) {
        return recipients(userId).stream()
                .filter(r -> r.recipientId() == recipientId)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RECIPIENT_NOT_FOUND));
    }

    /**
     * 그 통화의 최신 매매기준율(챗봇 INT-14). 환율 화면과 같은 조회(ExchangeRateReader): 우선 원천 하나의 최신 행,
     * 운영은 실데이터만. 없으면 404 FX_RATE_NOT_FOUND.
     */
    public AppliedRate latestRate(String currency) {
        return rateReader.latest(currency)
                .map(r -> new AppliedRate(r.currency(), r.unitAmount(), plain(r.baseRate()), r.sourceLabel(),
                        r.rateDate(), r.mock()))
                .orElseThrow(() -> new BusinessException(ErrorCode.FX_RATE_NOT_FOUND));
    }

    /**
     * 송금 견적(#121). amount 는 내 계좌에서 나갈 원화 총액(수수료 포함)이고 currency 는 KRW 만 받는다.
     * 송금 통화는 USD 이며 계산은 RemittanceQuoteCalculator 하나로 화면·챗봇이 같이 쓴다. 잔액은 보지 않는다(화면 API 만 확인).
     * - recipientId 가 없으면 첫 받는 분(어머니 먼저, TRF-01 과 같음). 받는 분이 없으면 404 RESOURCE_NOT_FOUND
     * - 환율은 환율 화면과 같은 조회(ExchangeRateReader, 운영은 실데이터만). USD 최신 값이 KOREAEXIM 이 아니거나 없으면
     *   404 FX_RATE_NOT_FOUND(스프레드가 신한 매매기준율 기준이라 수출입은행 고시만 쓴다)
     * - 국적이 PHP 참고 대상(필리핀)이 아니거나 PHP 환율이 없으면 PHP 참고값만 null
     * - appliedRate.sourceLabel 은 preferred_language 가 ko 면 한국어, 그 밖이면 영어(화면 TRF-02·챗봇 INT-18 같음)
     */
    public RemittanceQuoteResponse quote(long userId, Long recipientId, long amount, String currency) {
        if (amount < 1) {
            throw invalid("amount", "must be 1 or more");
        }
        if (!Money.KRW.equals(currency)) {
            throw invalid("currency", "must be KRW");
        }
        long resolved = recipientId == null ? firstRecipient(userId).recipientId()
                : recipient(userId, recipientId).recipientId();
        RemittanceQuoteCalculator.Rate usd = rateReader.latest("USD")
                .filter(r -> "KOREAEXIM".equals(r.source()))
                .map(RemittanceQuoteService::quoteRate)
                .orElseThrow(() -> new BusinessException(ErrorCode.FX_RATE_NOT_FOUND));
        RemittanceQuoteCalculator.Rate php = phpReference(userId)
                ? rateReader.latest(RemittanceQuoteCalculator.PHP).map(RemittanceQuoteService::quoteRate).orElse(null)
                : null;
        LocalDate today = LocalDate.now(KstDates.KST);
        try {
            return calculator.calculate(new RemittanceQuoteCalculator.Input(resolved, amount, usd, php,
                    pastRemittances(userId, today), today, userRepository.prefersEnglish(userId)));
        } catch (ArithmeticException e) {
            throw invalid("amount", "too large");
        }
    }

    private RemittanceRecipient firstRecipient(long userId) {
        return recipients(userId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 출처 이름은 MARKET_REF 면 원천 이름(frankfurter-ecb), 그 밖에는 source. */
    private static RemittanceQuoteCalculator.Rate quoteRate(ExchangeRateReader.DailyRate r) {
        return new RemittanceQuoteCalculator.Rate(r.baseRate(), r.rateDate(),
                r.provider() != null ? r.provider() : r.source(), r.mock());
    }

    /** 수취 국가는 사용자 국적(visa_profiles.nationality_code)으로 정한다. PHP 참고 대상 국적이면 true. */
    private boolean phpReference(long userId) {
        List<String> nationality = jdbcTemplate.queryForList(
                "SELECT nationality_code FROM visa_profiles WHERE user_id = ?", String.class, userId);
        return !nationality.isEmpty() && nationality.getFirst() != null
                && properties.phpReferenceNationalities().contains(nationality.getFirst());
    }

    /** 기준일 포함 과거 365일 해외송금 건수(마이데이터 거래, Mock). 이번 건은 계산기에서 더한다. */
    private int pastRemittances(long userId, LocalDate today) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM financial_transactions
                        WHERE user_id = ? AND category_code = 'REMITTANCE' AND direction = 'EXPENSE'
                          AND transaction_kind = 'NORMAL' AND booked_date BETWEEN ? AND ?""",
                Integer.class, userId, today.minusDays(364), today);
        return count == null ? 0 : count;
    }

    /** 끝자리 0 제거(Decimals.plain). 1350.00 → 1350 처럼 정수가 되면 지수 표기(1.35E+3)가 되지 않는다. */
    static BigDecimal plain(BigDecimal value) {
        return Decimals.plain(value);
    }

    static String last4(String masked) {
        String digits = masked == null ? "" : masked.replaceAll("\\D", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
