package com.hanstep.mydata.service;

import com.hanstep.mydata.domain.AccountType;
import com.hanstep.mydata.domain.TransactionDirection;
import com.hanstep.mydata.domain.TransactionKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mock 마이데이터 원천 파일(resources/mock/mydata/demo-e9-nguyen)을 읽음.
 * MVP 테스트 사용자는 한 명이라 로그인 사용자와 상관없이 이 페르소나 파일을 씀.
 * 원천 값을 칼럼에 옮기기만 하고, 칼럼이 없는 값(거래별 잔액, 카드 status·pay_type·billing_day 등)은 버림.
 */
@Component
public class MockMydataReader {

    private static final String BASE = "mock/mydata/demo-e9-nguyen/";
    private static final Map<String, String> FILES = Map.of(
            "SHINHAN_BANK", "bank-shinhan.json",
            "SHINHAN_CARD", "card-shinhan.json");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DTIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 기관 코드에 맞는 원천 파일. 파일이 없는 기관(MOCK_TELECOM 등)은 비어 있음. */
    public Optional<Source> read(String institutionCode) {
        String file = FILES.get(institutionCode);
        if (file == null) {
            return Optional.empty();
        }
        JsonNode root = readTree(file);
        String orgCode = root.path("org_code").asString();
        if (!institutionCode.equals(orgCode)) {
            throw new IllegalStateException("org_code mismatch: " + file);
        }
        JsonNode meta = root.path("_meta");
        LocalDate from = LocalDate.parse(meta.path("coverage_from").asString());
        LocalDate to = LocalDate.parse(meta.path("coverage_to").asString());
        List<Account> accounts = root.has("account_list") ? bankAccounts(root.path("account_list"))
                : cardAccounts(root.path("card_list"));
        return Optional.of(new Source(orgCode, from, to, accounts));
    }

    private static List<Account> bankAccounts(JsonNode list) {
        List<Account> accounts = new ArrayList<>();
        for (JsonNode a : list) {
            List<Transaction> txns = new ArrayList<>();
            for (JsonNode t : a.path("trans_list")) {
                LocalDateTime at = LocalDateTime.parse(t.path("trans_dtime").asString(), DTIME);
                txns.add(new Transaction(t.path("trans_key").asString(), toUtc(at), at.toLocalDate(),
                        direction(t.path("trans_type").asString()), amount(t, "trans_amt"),
                        t.path("currency_code").asString(), text(t, "counterparty_name"),
                        text(t, "counterparty_bank"), text(t, "trans_memo"), t.path("category_code").asString(),
                        TransactionKind.valueOf(t.path("transaction_kind").asString())));
            }
            accounts.add(new Account(a.path("account_key").asString(), a.path("account_num_masked").asString(),
                    AccountType.valueOf(a.path("account_type").asString()), text(a, "prod_name"),
                    a.path("currency_code").asString(), amount(a, "balance_amt"),
                    toUtc(LocalDateTime.parse(a.path("balance_as_of").asString(), DTIME)),
                    a.path("is_salary_account").asBoolean(), date(a, "issue_date"), txns));
        }
        return accounts;
    }

    /** 카드 승인은 trans_type 이 없음. 모두 APPROVED 라 지출(EXPENSE)로 넣고, 그 밖의 상태는 적재 실패. */
    private static List<Account> cardAccounts(JsonNode list) {
        List<Account> accounts = new ArrayList<>();
        for (JsonNode c : list) {
            List<Transaction> txns = new ArrayList<>();
            for (JsonNode t : c.path("approval_list")) {
                String status = t.path("status").asString();
                if (!"APPROVED".equals(status)) {
                    throw new IllegalStateException("unsupported card approval status: " + status);
                }
                LocalDateTime at = LocalDateTime.parse(t.path("approved_dtime").asString(), DTIME);
                txns.add(new Transaction(t.path("approved_key").asString(), toUtc(at), at.toLocalDate(),
                        TransactionDirection.EXPENSE, amount(t, "approved_amt"), t.path("currency_code").asString(),
                        text(t, "merchant_name"), null, text(t, "trans_memo"), t.path("category_code").asString(),
                        TransactionKind.valueOf(t.path("transaction_kind").asString())));
            }
            // 카드 원천에는 통화·잔액·급여계좌 여부가 없어 DB 기본값(KRW, 0, false)과 같은 값을 넣음
            accounts.add(new Account(c.path("card_key").asString(), c.path("card_num_masked").asString(),
                    AccountType.valueOf(c.path("account_type").asString()), text(c, "card_name"), "KRW",
                    BigDecimal.ZERO, null, false, date(c, "issue_date"), txns));
        }
        return accounts;
    }

    private static TransactionDirection direction(String transType) {
        return switch (transType) {
            case "DEPOSIT" -> TransactionDirection.INCOME;
            case "WITHDRAWAL" -> TransactionDirection.EXPENSE;
            default -> throw new IllegalStateException("unsupported trans_type: " + transType);
        };
    }

    /** *_dtime 은 KST. transacted_at 은 UTC 로 저장함. */
    private static Instant toUtc(LocalDateTime kst) {
        return kst.atZone(KST).toInstant();
    }

    private static BigDecimal amount(JsonNode node, String field) {
        return BigDecimal.valueOf(node.path(field).asLong());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDate.parse(value);
    }

    private static JsonNode readTree(String file) {
        try (InputStream in = new ClassPathResource(BASE + file).getInputStream()) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 기관 원천 파일 하나. coverage 는 _meta 의 수집 기간(KST 날짜). */
    public record Source(String orgCode, LocalDate coverageFrom, LocalDate coverageTo, List<Account> accounts) {
    }

    public record Account(String externalKey, String accountNoMasked, AccountType accountType, String productName,
                          String currency, BigDecimal balance, Instant balanceAsOf, boolean salaryAccount,
                          LocalDate openedOn, List<Transaction> transactions) {
    }

    public record Transaction(String externalKey, Instant transactedAt, LocalDate bookedDate,
                              TransactionDirection direction, BigDecimal amount, String currency,
                              String counterpartyName, String counterpartyBank, String memo, String categoryCode,
                              TransactionKind transactionKind) {
    }
}
