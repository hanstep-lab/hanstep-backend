package com.hanstep.mydata.repository;

import com.hanstep.mydata.domain.IngestionBatch;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionBatchRepository extends JpaRepository<IngestionBatch, Long> {

    Optional<IngestionBatch> findByUserIdAndRequestKey(Long userId, String requestKey);
}
