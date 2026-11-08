package com.hanstep.mydata.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.mydata.domain.BatchStatus;
import com.hanstep.mydata.domain.DataConnection;
import com.hanstep.mydata.domain.FinancialAccount;
import com.hanstep.mydata.domain.FinancialTransaction;
import com.hanstep.mydata.domain.IngestionBatch;
import com.hanstep.mydata.domain.MydataScope;
import com.hanstep.mydata.repository.DataConnectionRepository;
import com.hanstep.mydata.repository.FinancialAccountRepository;
import com.hanstep.mydata.repository.FinancialTransactionRepository;
import com.hanstep.mydata.repository.IngestionBatchRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * MYD-02 의 적재 부분. ingestion_batches → data_connections · financial_accounts → financial_transactions.
 *
 * <p>트랜잭션을 셋으로 나눈다. ① PENDING 배치 INSERT(바로 커밋, 같은 키 동시 요청은 UNIQUE 충돌)
 * ② 적재 + SUCCEEDED ③ 실패하면 FAILED. 그래서 이 메서드는 @Transactional 을 붙이지 않는다.
 */
@Service
public class MockIngestionService {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final IngestionBatchRepository batchRepository;
    private final DataConnectionRepository connectionRepository;
    private final FinancialAccountRepository accountRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final TransactionTemplate tx;

    public MockIngestionService(IngestionBatchRepository batchRepository,
                                DataConnectionRepository connectionRepository,
                                FinancialAccountRepository accountRepository,
                                FinancialTransactionRepository transactionRepository,
                                TransactionTemplate tx) {
        this.batchRepository = batchRepository;
        this.connectionRepository = connectionRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.tx = tx;
    }

    /**
     * @return true 면 이번 요청이 적재했다. false 면 같은 키가 이미 SUCCEEDED(재요청).
     * @throws BusinessException 같은 키가 처리 중(PENDING)이면 DUPLICATE_REQUEST, 실패(FAILED)했으면 INVALID_STATE
     */
    public boolean ingest(Long userId, String requestKey, Long consentId, List<MydataScope> scopes,
                          int lookbackMonths, List<Target> targets) {
        Long batchId;
        try {
            batchId = tx.execute(s -> batchRepository.saveAndFlush(
                    IngestionBatch.startMock(userId, requestKey, now())).getId());
        } catch (DataIntegrityViolationException e) {
            IngestionBatch first = batchRepository.findByUserIdAndRequestKey(userId, requestKey).orElseThrow(() -> e);
            if (first.getStatus() == BatchStatus.SUCCEEDED) {
                return false;
            }
            throw new BusinessException(first.getStatus() == BatchStatus.PENDING
                    ? ErrorCode.DUPLICATE_REQUEST : ErrorCode.INVALID_STATE);
        }

        try {
            tx.executeWithoutResult(s -> load(batchId, userId, consentId, scopes, lookbackMonths, targets));
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> batchRepository.findById(batchId)
                    .ifPresent(b -> b.fail("INGESTION_FAILED", now())));
            throw e;
        }
        return true;
    }

    private void load(Long batchId, Long userId, Long consentId, List<MydataScope> scopes, int lookbackMonths,
                      List<Target> targets) {
        IngestionBatch batch = batchRepository.findById(batchId).orElseThrow();
        String scopesJson = MAPPER.writeValueAsString(scopes);
        Instant syncedAt = now();
        LocalDate coverageFrom = null;
        LocalDate coverageTo = null;
        int inserted = 0;

        for (Target target : targets) {
            DataConnection connection = connectionRepository
                    .findByUserIdAndInstitutionId(userId, target.institutionId())
                    .orElseGet(() -> new DataConnection(userId, target.institutionId()));
            connection.connect(consentId, scopesJson, lookbackMonths, syncedAt);
            connectionRepository.save(connection);

            MockMydataReader.Source source = target.source();
            if (source == null) {
                continue; // 원천 파일이 없는 기관은 연결만
            }
            // 최근 lookbackMonths 개월(coverage_to 기준). 12개월이면 원천 전체
            LocalDate from = later(source.coverageFrom(), source.coverageTo().plusDays(1).minusMonths(lookbackMonths));
            coverageFrom = coverageFrom == null || from.isBefore(coverageFrom) ? from : coverageFrom;
            coverageTo = coverageTo == null || source.coverageTo().isAfter(coverageTo) ? source.coverageTo()
                    : coverageTo;

            for (MockMydataReader.Account a : source.accounts()) {
                FinancialAccount account = accountRepository
                        .findByUserIdAndInstitutionIdAndExternalAccountKey(userId, target.institutionId(),
                                a.externalKey())
                        .orElse(null);
                if (account == null) {
                    account = accountRepository.save(new FinancialAccount(userId, target.institutionId(), batchId,
                            a.externalKey(), a.accountNoMasked(), a.accountType(), a.productName(), a.currency(),
                            a.balance(), a.balanceAsOf(), a.salaryAccount(), a.openedOn()));
                } else {
                    account.refreshBalance(a.balance(), a.balanceAsOf());
                }

                Long accountId = account.getId();
                Set<String> existing = new HashSet<>(transactionRepository.findExternalTxnKeysByAccountId(accountId));
                List<FinancialTransaction> fresh = a.transactions().stream()
                        .filter(t -> !t.bookedDate().isBefore(from))
                        .filter(t -> existing.add(t.externalKey()))
                        .map(t -> new FinancialTransaction(userId, accountId, batchId, t.externalKey(),
                                t.transactedAt(), t.bookedDate(), t.direction(), t.amount(), t.currency(),
                                t.counterpartyName(), t.counterpartyBank(), t.memo(), t.categoryCode(),
                                t.transactionKind()))
                        .toList();
                transactionRepository.saveAll(fresh); // A안: JPA saveAll(IDENTITY 라 행마다 INSERT 1번)
                inserted += fresh.size();
            }
        }
        batch.succeed(coverageFrom, coverageTo, inserted, now());
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** 연결할 기관과 그 원천. 원천 파일이 없는 기관은 source 가 null. */
    public record Target(Long institutionId, MockMydataReader.Source source) {
    }
}
