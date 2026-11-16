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
}
