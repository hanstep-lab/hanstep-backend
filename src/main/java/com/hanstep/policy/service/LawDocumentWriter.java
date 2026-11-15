package com.hanstep.policy.service;

import com.hanstep.aijob.domain.AiJobResourceType;
import com.hanstep.aijob.domain.AiJobType;
import com.hanstep.aijob.service.AiJobEnqueueService;
import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.repository.PolicyDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 법령 문서와 POLICY_INGEST 작업을 한 트랜잭션으로 저장한다. 커밋되면 작업이 RabbitMQ 로 발행된다.
 * content_hash 가 겹치면(동시 요청) DataIntegrityViolationException 으로 문서·작업이 같이 롤백되고 발행도 없다.
 * LawDocumentRegisterService 가 이 예외를 트랜잭션 밖에서 잡아야 해서 별도 빈으로 둔다.
 */
@Service
public class LawDocumentWriter {

    private final PolicyDocumentRepository policyDocumentRepository;
    private final AiJobEnqueueService aiJobEnqueueService;

    public LawDocumentWriter(PolicyDocumentRepository policyDocumentRepository,
                             AiJobEnqueueService aiJobEnqueueService) {
        this.policyDocumentRepository = policyDocumentRepository;
        this.aiJobEnqueueService = aiJobEnqueueService;
    }

    @Transactional
    public PolicyDocument create(PolicyDocument doc) {
        PolicyDocument saved = policyDocumentRepository.saveAndFlush(doc);
        // 작업 멱등 키는 등록 멱등 키(Idempotency-Key = contentHash)와 같다. 문서 하나에 작업 하나
        aiJobEnqueueService.enqueue(AiJobType.POLICY_INGEST, AiJobResourceType.POLICY_DOCUMENT, saved.getId(), null,
                saved.getContentHash());
        return saved;
    }
}
