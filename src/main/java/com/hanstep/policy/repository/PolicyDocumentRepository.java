package com.hanstep.policy.repository;

import com.hanstep.policy.domain.PolicyDocument;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {

    // 같은 문서의 적재 완료 보고가 동시에 와도 object_key 를 "비어 있을 때만" 채우도록 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PolicyDocument d where d.id = :id")
    Optional<PolicyDocument> findByIdForUpdate(Long id);
}
