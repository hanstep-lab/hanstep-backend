package com.hanstep.policy.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.dto.PolicyDocumentStateItem;
import com.hanstep.policy.dto.PolicyDocumentStateResponse;
import com.hanstep.policy.dto.PolicyIngestedRequest;
import com.hanstep.policy.dto.SearchEligibilityItem;
import com.hanstep.policy.dto.SearchEligibilityResponse;
import com.hanstep.policy.repository.PolicyDocumentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AI → Spring 정책 문서 내부 API(AI-R1, INT-13, 상태 목록). */
@Service
public class PolicyDocumentInternalService {

    static final int MAX_IDS = 20;
    static final int EMBEDDING_MODEL_MAX_LENGTH = 100;
    static final int MAX_STATE_DOCUMENTS = 1000;

    private final PolicyDocumentRepository policyDocumentRepository;
    private final Clock clock;

    public PolicyDocumentInternalService(PolicyDocumentRepository policyDocumentRepository) {
        this.policyDocumentRepository = policyDocumentRepository;
        this.clock = Clock.system(KstDates.KST);
    }

    @Transactional(readOnly = true)
    public SearchEligibilityResponse searchEligibility(String ids) {
        Set<Long> parsed = parseIds(ids);
        LocalDate today = LocalDate.now(clock);
        List<SearchEligibilityItem> documents = policyDocumentRepository.findAllById(parsed).stream()
                .sorted(Comparator.comparing(PolicyDocument::getId))
                .map(doc -> SearchEligibilityItem.of(doc, today))
                .toList();
        return new SearchEligibilityResponse(documents);
    }

    /**
     * AI 상태 대조용 전체 목록(id 순). AI 는 이 목록을 모든 행으로 보고 빠진 문서를 고아로 끄므로(--complete-snapshot)
     * 상한을 넘으면 잘라서 주지 않고 409 로 알린다.
     */
    @Transactional(readOnly = true)
    public PolicyDocumentStateResponse states() {
        long total = policyDocumentRepository.count();
        if (total > MAX_STATE_DOCUMENTS) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "too many policy documents for one snapshot",
                    Map.of("reason", "TOO_MANY_DOCUMENTS", "limit", MAX_STATE_DOCUMENTS, "total", total));
        }
        LocalDate today = LocalDate.now(clock);
        List<PolicyDocumentStateItem> documents = policyDocumentRepository.findAll(Sort.by("id")).stream()
                .map(doc -> PolicyDocumentStateItem.of(doc, today))
                .toList();
        return new PolicyDocumentStateResponse(documents);
    }

    @Transactional
    public void recordIngested(Long policyDocumentId, PolicyIngestedRequest request) {
        PolicyDocument doc = policyDocumentRepository.findByIdForUpdate(policyDocumentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        Map.of("policyDocumentId", policyDocumentId)));
        if (!doc.getPolicyVersion().equals(request.documentVersion())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "documentVersion does not match",
                    Map.of("field", "documentVersion", "expected", doc.getPolicyVersion()));
        }
        if (!doc.getContentHash().equals(request.contentHash())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "contentHash does not match",
                    Map.of("field", "contentHash"));
        }
        String embeddingModel = request.embeddingModel() + "@" + request.embeddingRevision();
        if (embeddingModel.length() > EMBEDDING_MODEL_MAX_LENGTH) {
            throw validation("embeddingModel", "embeddingModel@embeddingRevision exceeds "
                    + EMBEDDING_MODEL_MAX_LENGTH + " chars");
        }
        doc.recordIngestion(request.chunkCount(), request.mongoCollection(), embeddingModel,
                request.objectKey(), request.ingestedAt().toInstant());
    }

    // "12,15,31" → 1~20개 양의 정수. 중복은 한 번만.
    private static Set<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            throw validation("ids", "required");
        }
        String[] parts = ids.split(",", -1);
        if (parts.length > MAX_IDS) {
            throw validation("ids", "at most " + MAX_IDS + " ids");
        }
        Set<Long> parsed = new LinkedHashSet<>();
        for (String part : parts) {
            String trimmed = part.strip();
            if (!trimmed.matches("[0-9]{1,18}") || Long.parseLong(trimmed) == 0) {
                throw validation("ids", "comma-separated positive integers");
            }
            parsed.add(Long.parseLong(trimmed));
        }
        return parsed;
    }

    private static BusinessException validation(String field, String reason) {
        List<Map<String, String>> fields = new ArrayList<>();
        fields.add(Map.of("field", field, "reason", reason));
        return new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("fields", fields));
    }
}
