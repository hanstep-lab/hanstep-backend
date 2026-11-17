package com.hanstep.document.repository;

import com.hanstep.document.domain.Document;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    // 같은 문서에 OCR 결과가 동시에 두 번 와도(재전송) 하나씩 반영되게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdForUpdate(Long id);

    /** 같은 사진 다시 올림 판별(uq_documents_user_hash). */
    Optional<Document> findByUserIdAndSha256(Long userId, String sha256);

    /** 본인 문서만. 남의 문서는 없는 것으로 본다(404). */
    Optional<Document> findByIdAndUserId(Long id, Long userId);
}
