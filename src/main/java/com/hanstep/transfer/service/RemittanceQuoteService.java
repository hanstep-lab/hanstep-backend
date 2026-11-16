package com.hanstep.transfer.service;

import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.transfer.dto.AppliedRate;
import com.hanstep.transfer.dto.RemittanceQuoteResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private static final String LABEL_KOREAEXIM = "수출입은행 고시 매매기준율";
    private static final String LABEL_REFERENCE = "ECB 기준환율 기반 참고값";

    private final JdbcTemplate jdbcTemplate;

    public RemittanceQuoteService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
     * currency=KRW 면 amount 는 보낼 원화 → 받을 외화 = 원화 × unit ÷ rate(1단위 버림).
     * currency=받는 분 통화면 amount 는 받을 외화 → 보낼 원화 = 외화 × rate ÷ unit(원 단위 올림).
     */
    public RemittanceQuoteResponse quote(long userId, long recipientId, long amount, String currency) {
        if (amount < 1) {
            throw invalid("amount", "must be 1 or more");
        }
        RemittanceRecipient recipient = recipient(userId, recipientId);
        boolean fromKrw = Money.KRW.equals(currency);
        if (!fromKrw && !recipient.currency().equals(currency)) {
            throw invalid("currency", "must be KRW or " + recipient.currency());
        }
        AppliedRate rate = latestRate(recipient.currency());
        BigDecimal unit = BigDecimal.valueOf(rate.unit());
        BigDecimal requested = BigDecimal.valueOf(amount);
        try {
            if (fromKrw) {
                long receive = requested.multiply(unit).divide(rate.rate(), 0, RoundingMode.DOWN).longValueExact();
                return new RemittanceQuoteResponse(recipientId, Money.krw(amount),
                        new Money(receive, recipient.currency()), rate);
            }
            long send = requested.multiply(rate.rate()).divide(unit, 0, RoundingMode.CEILING).longValueExact();
            return new RemittanceQuoteResponse(recipientId, Money.krw(send), new Money(amount, recipient.currency()),
                    rate);
        } catch (ArithmeticException e) {
            throw invalid("amount", "too large");
        }
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
