package com.hanstep.aijob.repository;

import com.hanstep.aijob.domain.AiJob;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AiJobRepository extends JpaRepository<AiJob, Long> {

    // 같은 작업에 started 가 동시에 두 번 와도 한 번만 시작되게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AiJob j where j.id = :id")
    Optional<AiJob> findByIdForUpdate(Long id);
}
