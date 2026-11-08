package com.hanstep.mydata.repository;

import com.hanstep.mydata.domain.FinancialTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, Long> {

    /** 배치가 실제로 넣은 거래 수(중복 키로 건너뛴 행 제외). */
    int countByBatchId(Long batchId);

    @Query("select count(t) from FinancialTransaction t where t.userId = :userId and t.accountId in "
            + "(select a.id from FinancialAccount a where a.userId = :userId and a.institutionId = :institutionId)")
    long countByUserIdAndInstitutionId(@Param("userId") Long userId, @Param("institutionId") Long institutionId);
}
