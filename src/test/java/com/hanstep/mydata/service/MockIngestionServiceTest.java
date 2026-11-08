package com.hanstep.mydata.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanstep.mydata.domain.MydataScope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 묶음 INSERT 는 중복 키만 건너뛰고(ON DUPLICATE KEY UPDATE), 외래키 위반은 적재 실패여야 한다.
 * FAILED 배치가 커밋돼야 하므로 롤백하지 않고, 테스트 사용자를 끝나면 지운다(CASCADE).
 */
@SpringBootTest
class MockIngestionServiceTest {

    private static final String TEST_USER_KEY = "test-mydata-ingestion";

    @Autowired
    private MockIngestionService ingestionService;

    @Autowired
    private MockMydataReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long consentId;

    @BeforeEach
    void setUp() {
        deleteTestUser();
        jdbcTemplate.update("INSERT INTO users (external_user_key, display_name) VALUES (?, 'TEST INGESTION')",
                TEST_USER_KEY);
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class,
                TEST_USER_KEY);
        jdbcTemplate.update("INSERT INTO user_consents (user_id, consent_type, policy_version, status, granted_at) "
                + "VALUES (?, 'MYDATA', 'test', 'GRANTED', NOW(3))", userId);
        consentId = jdbcTemplate.queryForObject("SELECT id FROM user_consents WHERE user_id = ?", Long.class,
                userId);
    }

    @AfterEach
    void tearDown() {
        deleteTestUser();
    }

    @Test
    void 기준표에_없는_category_code가_있으면_FAILED이고_거래는_0건() {
        MockMydataReader.Source bank = withOneUnknownCategory(reader.read("SHINHAN_BANK").orElseThrow());
        Long bankId = jdbcTemplate.queryForObject("SELECT id FROM institutions WHERE institution_code = "
                + "'SHINHAN_BANK'", Long.class);

        assertThatThrownBy(() -> ingestionService.ingest(userId, "key-fk", consentId,
                List.of(MydataScope.ACCOUNT_BALANCE, MydataScope.TRANSACTIONS), 12,
                List.of(new MockIngestionService.Target(bankId, bank))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_batches WHERE user_id = ? "
                + "AND request_key = 'key-fk'", String.class, userId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM financial_transactions WHERE user_id = ?",
                Long.class, userId)).isZero();
    }

    /** 마지막 거래 1건만 category_code 를 기준표에 없는 값으로 바꾼다(앞 행들은 정상). */
    private static MockMydataReader.Source withOneUnknownCategory(MockMydataReader.Source source) {
        AtomicBoolean changed = new AtomicBoolean();
        List<MockMydataReader.Account> accounts = source.accounts().stream()
                .map(a -> {
                    List<MockMydataReader.Transaction> txns = new ArrayList<>(a.transactions());
                    if (!txns.isEmpty() && changed.compareAndSet(false, true)) {
                        MockMydataReader.Transaction t = txns.get(txns.size() - 1);
                        txns.set(txns.size() - 1, new MockMydataReader.Transaction(t.externalKey(),
                                t.transactedAt(), t.bookedDate(), t.direction(), t.amount(), t.currency(),
                                t.counterpartyName(), t.counterpartyBank(), t.memo(), "NO_SUCH_CATEGORY",
                                t.transactionKind()));
                    }
                    return new MockMydataReader.Account(a.externalKey(), a.accountNoMasked(), a.accountType(),
                            a.productName(), a.currency(), a.balance(), a.balanceAsOf(), a.salaryAccount(),
                            a.openedOn(), txns);
                })
                .toList();
        assertThat(changed).isTrue();
        return new MockMydataReader.Source(source.orgCode(), source.coverageFrom(), source.coverageTo(), accounts);
    }

    private void deleteTestUser() {
        jdbcTemplate.update("DELETE b FROM ingestion_batches b JOIN users u ON u.id = b.user_id "
                + "WHERE u.external_user_key = ?", TEST_USER_KEY);
        jdbcTemplate.update("DELETE c FROM data_connections c JOIN users u ON u.id = c.user_id "
                + "WHERE u.external_user_key = ?", TEST_USER_KEY);
        jdbcTemplate.update("DELETE FROM users WHERE external_user_key = ?", TEST_USER_KEY);
    }
}
