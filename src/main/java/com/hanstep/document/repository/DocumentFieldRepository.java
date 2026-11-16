package com.hanstep.document.repository;

import com.hanstep.document.domain.DocumentField;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentFieldRepository extends JpaRepository<DocumentField, Long> {

    List<DocumentField> findByDocumentId(Long documentId);
}
