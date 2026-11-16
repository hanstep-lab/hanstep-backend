package com.hanstep.aijob.repository;

import com.hanstep.aijob.domain.AiJob;
import com.hanstep.aijob.domain.AiJobResourceType;
import com.hanstep.aijob.domain.AiJobType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AiJobRepository extends JpaRepository<AiJob, Long> {

    // 같은 작업에 started 가 동시에 두 번 와도 한 번만 시작되게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AiJob j where j.id = :id")
    Optional<AiJob> findByIdForUpdate(Long id);

    // 적재 완료(INT-13)와 started 가 같은 작업에 동시에 와도 순서대로 반영되게 같은 방식으로 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AiJob j where j.jobType = :jobType and j.resourceType = :resourceType"
            + " and j.resourceId = :resourceId")
    List<AiJob> findByResourceForUpdate(AiJobType jobType, AiJobResourceType resourceType, Long resourceId);
}
