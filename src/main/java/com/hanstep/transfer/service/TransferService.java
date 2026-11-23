package com.hanstep.transfer.service;

import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.transfer.dto.RemittanceQuoteResponse;
import com.hanstep.transfer.dto.TransferContextResponse;
import com.hanstep.transfer.dto.TransferRecipientResponse;
import com.hanstep.user.repository.UserRepository;
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
    // preferred_language 가 ko 가 아니면 괄호 안 영어만
    private static final TransferContextResponse.Purpose FAMILY_SUPPORT_EN =
            new TransferContextResponse.Purpose("FAMILY_SUPPORT", "Family support");
    private static final TransferContextResponse.Purpose OWN_ACCOUNT_EN =
            new TransferContextResponse.Purpose("OWN_ACCOUNT", "Own account");
    // 계좌 상품명 영어(데모 Mock 상품명만). 없는 이름은 원문 그대로
    private static final Map<String, String> ACCOUNT_NAMES_EN = Map.of("신한 주거래 우대통장",
            "Shinhan Main Bank Preferred Account");

    private final RemittanceQuoteService quoteService;
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;

    public TransferService(RemittanceQuoteService quoteService, JdbcTemplate jdbcTemplate,
                           UserRepository userRepository) {
        this.quoteService = quoteService;
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
    }

    private record AccountRow(String name, String number, Money balance, boolean mock) {
    }

    /**
     * TRF-01. 받는 분이 하나도 없으면 404 RESOURCE_NOT_FOUND, recipientId 가 본인 받는 분이 아니면 404 RECIPIENT_NOT_FOUND,
     * 출금 계좌가 없으면 422 INSUFFICIENT_DATA. 관계·목적·계좌 이름은 preferred_language 가 ko 가 아니면 영어.
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
        boolean en = userRepository.prefersEnglish(userId);
        boolean self = "SELF".equals(selected.relationship());
        String accountName = en && account.name() != null
                ? ACCOUNT_NAMES_EN.getOrDefault(account.name(), account.name()) : account.name();
        return new TransferContextResponse(
                TransferRecipientResponse.from(selected, en),
                recipients.stream().map(r -> TransferRecipientResponse.from(r, en)).toList(),
                new TransferContextResponse.Account(accountName, account.number(), account.balance()),
                en ? (self ? OWN_ACCOUNT_EN : FAMILY_SUPPORT_EN) : (self ? OWN_ACCOUNT : FAMILY_SUPPORT),
                QUICK_AMOUNTS,
                selected.mock() || account.mock());
    }

    /** TRF-02. 견적 후 출금할 원화 총액(요청 amount)이 출금 계좌 잔액보다 크면 422 INSUFFICIENT_BALANCE. */
    public RemittanceQuoteResponse quote(long userId, Long recipientId, long amount, String currency) {
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
