package com.hanstep.policy.repository;

import com.hanstep.policy.domain.PolicyDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {
}
