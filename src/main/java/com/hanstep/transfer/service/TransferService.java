package com.hanstep.transfer.service;

import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.transfer.dto.RemittanceQuoteResponse;
import com.hanstep.transfer.dto.TransferContextResponse;
import com.hanstep.transfer.dto.TransferRecipientResponse;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** TRF-01·02. 해외송금 화면 조회와 견적(잔액 확인 포함). 송금 실행은 없다. */
@Service
@Transactional(readOnly = true)
public class TransferService {

    // 임시값: 화면 문구·금액이 정해지면 바꾼다
    private static final List<Long> QUICK_AMOUNTS = List.of(100_000L, 500_000L, 1_000_000L);
    private static final TransferContextResponse.Purpose FAMILY_SUPPORT =
            new TransferContextResponse.Purpose("FAMILY_SUPPORT", "가족 생활비 (Family support)");
    private static final TransferContextResponse.Purpose OWN_ACCOUNT =
            new TransferContextResponse.Purpose("OWN_ACCOUNT", "본인 계좌 송금 (Own account)");

    private final RemittanceQuoteService quoteService;
    private final JdbcTemplate jdbcTemplate;

    public TransferService(RemittanceQuoteService quoteService, JdbcTemplate jdbcTemplate) {
        this.quoteService = quoteService;
        this.jdbcTemplate = jdbcTemplate;
    }

    private record AccountRow(String name, String number, Money balance, boolean mock) {
    }

    /**
     * TRF-01. 받는 분이 하나도 없으면 404 RESOURCE_NOT_FOUND, recipientId 가 본인 받는 분이 아니면 404 RECIPIENT_NOT_FOUND,
     * 출금 계좌가 없으면 422 INSUFFICIENT_DATA.
     */
    public TransferContextResponse context(long userId, Long recipientId) {
        List<RemittanceRecipient> recipients = quoteService.recipients(userId);
        if (recipients.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        RemittanceRecipient selected = recipientId == null
                ? recipients.getFirst() // MOTHER 우선 정렬
                : quoteService.recipient(userId, recipientId);
        AccountRow account = account(userId);
        return new TransferContextResponse(
                TransferRecipientResponse.from(selected),
                recipients.stream().map(TransferRecipientResponse::from).toList(),
                new TransferContextResponse.Account(account.name(), account.number(), account.balance()),
                "SELF".equals(selected.relationship()) ? OWN_ACCOUNT : FAMILY_SUPPORT,
                QUICK_AMOUNTS,
                selected.mock() || account.mock());
    }

    /** TRF-02. 견적 후 보낼 원화가 출금 계좌 잔액보다 크면 422 INSUFFICIENT_BALANCE. */
    public RemittanceQuoteResponse quote(long userId, long recipientId, long amount, String currency) {
        RemittanceQuoteResponse quote = quoteService.quote(userId, recipientId, amount, currency);
        Money balance = account(userId).balance();
        if (quote.sendAmount().amount() > balance.amount()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE,
                    Map.of("sendAmount", quote.sendAmount(), "balance", balance));
        }
        return quote;
    }

    /** 해지 안 된 원화 계좌 중 급여 계좌, 없으면 입출금(DEMAND) 계좌 1개. 없으면 422 INSUFFICIENT_DATA. */
    private AccountRow account(long userId) {
        return jdbcTemplate.query("""
                        SELECT a.product_name, a.account_no_masked, a.balance, b.is_mock
                        FROM financial_accounts a
                        JOIN ingestion_batches b ON b.id = a.batch_id
                        WHERE a.user_id = ? AND a.closed_on IS NULL AND a.currency = 'KRW'
                          AND (a.is_salary_account OR a.account_type = 'DEMAND')
                        ORDER BY a.is_salary_account DESC, a.id
                        LIMIT 1""",
                (rs, i) -> new AccountRow(rs.getString("product_name"), rs.getString("account_no_masked"),
                        Money.krw(rs.getBigDecimal("balance").setScale(0, RoundingMode.DOWN).longValueExact()),
                        rs.getBoolean("is_mock")),
                userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_DATA,
                        Map.of("reason", "NO_ACCOUNT")));
    }
}
