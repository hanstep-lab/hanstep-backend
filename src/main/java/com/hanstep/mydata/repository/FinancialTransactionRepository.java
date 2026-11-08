package com.hanstep.mydata.repository;

import com.hanstep.mydata.domain.FinancialTransaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, Long> {

    /** 재수집 중복 확인용. 계좌의 기존 거래 키를 한 번에 가져온다. */
    @Query("select t.externalTxnKey from FinancialTransaction t where t.accountId = :accountId")
    List<String> findExternalTxnKeysByAccountId(@Param("accountId") Long accountId);

    @Query("select count(t) from FinancialTransaction t where t.userId = :userId and t.accountId in "
            + "(select a.id from FinancialAccount a where a.userId = :userId and a.institutionId = :institutionId)")
    long countByUserIdAndInstitutionId(@Param("userId") Long userId, @Param("institutionId") Long institutionId);
}
