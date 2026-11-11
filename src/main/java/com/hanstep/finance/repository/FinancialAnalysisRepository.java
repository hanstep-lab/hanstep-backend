package com.hanstep.finance.repository;

import com.hanstep.finance.domain.FinancialAnalysis;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FinancialAnalysisRepository extends JpaRepository<FinancialAnalysis, Long> {

    Optional<FinancialAnalysis> findByUserIdAndRequestKey(Long userId, String requestKey);

    /**
     * 최신 COMPLETED 분석 1건과 분석에 쓴 배치(source_batch_id)의 is_mock 을 SQL 1번으로.
     * [FinancialAnalysis, Boolean(배치가 지워졌으면 null)].
     */
    @Query("""
            select a, (select b.mock from IngestionBatch b where b.id = a.sourceBatchId)
            from FinancialAnalysis a
            where a.userId = :userId and a.status = com.hanstep.finance.domain.AnalysisStatus.COMPLETED
            order by a.id desc
            limit 1""")
    List<Object[]> findLatestCompletedWithMock(Long userId);
}
