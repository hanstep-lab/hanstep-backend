package com.hanstep.policy.repository;

import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.domain.PolicySourceType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {

    Optional<PolicyDocument> findByContentHash(String contentHash);

    boolean existsByPolicyVersion(String policyVersion);

    // 같은 문서의 적재 완료 보고가 동시에 와도 object_key 를 "비어 있을 때만" 채우도록 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PolicyDocument d where d.id = :id")
    Optional<PolicyDocument> findByIdForUpdate(Long id);

    // #42: 같은 문서(source_type·external_id)의 모든 버전을 id 순으로 잠근다(같은 문서의 두 버전을 동시에 승인해도 차례로 처리)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PolicyDocument d where d.sourceType = :sourceType and d.externalId = :externalId order by d.id")
    List<PolicyDocument> findVersionsForUpdate(PolicySourceType sourceType, String externalId);
}
