package com.hanstep.transfer.service;

import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.transfer.config.RemittanceProperties;
import com.hanstep.transfer.dto.AppliedRate;
import com.hanstep.transfer.dto.RemittanceQuoteResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final String LABEL_KOREAEXIM = "수출입은행 고시 매매기준율";
    private static final String LABEL_REFERENCE = "ECB 기준환율 기반 참고값";

    private final JdbcTemplate jdbcTemplate;
    private final RemittanceQuoteCalculator calculator;
    private final RemittanceProperties properties;

    public RemittanceQuoteService(JdbcTemplate jdbcTemplate, RemittanceQuoteCalculator calculator,
                                  RemittanceProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.calculator = calculator;
        this.properties = properties;
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

    /** 그 통화의 가장 최근 기준일(같은 날이면 마지막 시각) 매매기준율. 없으면 404 FX_RATE_NOT_FOUND. */
    public AppliedRate latestRate(String currency) {
        return jdbcTemplate.query("""
                        SELECT currency, unit_amount, base_rate, source, rate_date, is_mock
                        FROM exchange_rates
                        WHERE currency = ?
                        ORDER BY rate_date DESC, rate_at DESC
                        LIMIT 1""",
                (rs, i) -> new AppliedRate(rs.getString("currency"), rs.getInt("unit_amount"),
                        plain(rs.getBigDecimal("base_rate")), sourceLabel(rs.getString("source")),
                        rs.getObject("rate_date", LocalDate.class), rs.getBoolean("is_mock")),
                currency)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FX_RATE_NOT_FOUND));
    }

    /**
     * 송금 견적(#121). amount 는 내 계좌에서 나갈 원화 총액(수수료 포함)이고 currency 는 KRW 만 받는다.
     * 송금 통화는 USD 이며 계산은 RemittanceQuoteCalculator 하나로 화면·챗봇이 같이 쓴다. 잔액은 보지 않는다(화면 API 만 확인).
     * - recipientId 가 없으면 첫 받는 분(어머니 먼저, TRF-01 과 같음). 받는 분이 없으면 404 RESOURCE_NOT_FOUND
     * - USD 매매기준율 실데이터(KOREAEXIM, is_mock = FALSE)가 없으면 404 FX_RATE_NOT_FOUND
     * - 국적이 PHP 참고 대상(필리핀)이 아니거나 PHP 참고 환율 실데이터가 없으면 PHP 참고값만 null
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
        RemittanceQuoteCalculator.Rate usd = latestRealRate("""
                        SELECT base_rate, rate_date FROM exchange_rates
                        WHERE currency = 'USD' AND source = 'KOREAEXIM' AND NOT is_mock
                        ORDER BY rate_date DESC, rate_at DESC LIMIT 1""")
                .orElseThrow(() -> new BusinessException(ErrorCode.FX_RATE_NOT_FOUND));
        RemittanceQuoteCalculator.Rate php = phpReference(userId) ? latestRealRate("""
                        SELECT base_rate, rate_date FROM exchange_rates
                        WHERE currency = 'PHP' AND source = 'MARKET_REF' AND provider = ? AND NOT is_mock
                        ORDER BY rate_date DESC, rate_at DESC LIMIT 1""", RemittanceQuoteCalculator.PHP_SOURCE)
                .orElse(null) : null;
        LocalDate today = LocalDate.now(KstDates.KST);
        try {
            return calculator.calculate(new RemittanceQuoteCalculator.Input(resolved, amount, usd, php,
                    pastRemittances(userId, today), today));
        } catch (ArithmeticException e) {
            throw invalid("amount", "too large");
        }
    }

    private RemittanceRecipient firstRecipient(long userId) {
        return recipients(userId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Optional<RemittanceQuoteCalculator.Rate> latestRealRate(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, i) -> new RemittanceQuoteCalculator.Rate(rs.getBigDecimal("base_rate"),
                rs.getObject("rate_date", LocalDate.class)), args).stream().findFirst();
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

    /** 끝자리 0 제거. 1350.00 → 1350 처럼 정수가 되면 지수 표기(1.35E+3)가 되지 않게 scale 을 0 으로 맞춘다. */
    static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    static String last4(String masked) {
        String digits = masked == null ? "" : masked.replaceAll("\\D", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

    private static String sourceLabel(String source) {
        return "KOREAEXIM".equals(source) ? LABEL_KOREAEXIM : LABEL_REFERENCE;
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
