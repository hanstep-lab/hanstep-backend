package com.hanstep.policy.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.domain.PolicySourceType;
import com.hanstep.policy.domain.ReviewStatus;
import com.hanstep.policy.dto.PolicyDocumentReviewRequest;
import com.hanstep.policy.dto.PolicyDocumentReviewResponse;
import com.hanstep.policy.dto.PolicyDocumentReviewResponse.Change;
import com.hanstep.policy.repository.PolicyDocumentRepository;
import com.hanstep.user.domain.UserRole;
import com.hanstep.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 정책 문서 검토(#53)와 이전 버전 정리(#42). 승인하면 같은 트랜잭션으로 같은 문서(source_type·external_id)의
 * 이전 승인 버전을 정리하고, 바뀐 행마다 policy_document_reviews 에 이력을 남긴다.
 * MongoDB 청크 상태는 바꾸지 않는다(AI 가 상태 목록 API 로 대조하고, 응답의 changed 가 그 대상).
 * 이력은 JdbcTemplate 으로 넣는다(엔티티로 만들면 테이블이 없는 기존 볼륨에서 앱이 뜨지 않음, ddl-auto=validate).
 */
@Service
public class PolicyDocumentReviewService {

    static final String NOT_INGESTED = "NOT_INGESTED";

    private static final String INSERT_REVIEW = """
            INSERT INTO policy_document_reviews (policy_document_id, reason, from_status, to_status, from_effective_from,
                from_effective_to, to_effective_from, to_effective_to, superseded_by, reviewed_by, note)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final PolicyDocumentRepository policyDocumentRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PolicyDocumentReviewService(PolicyDocumentRepository policyDocumentRepository,
                                       UserRepository userRepository, JdbcTemplate jdbcTemplate) {
        this.policyDocumentRepository = policyDocumentRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = Clock.system(KstDates.KST);
    }

    @Transactional
    public PolicyDocumentReviewResponse review(Long adminId, Long policyDocumentId, PolicyDocumentReviewRequest request) {
        requireAdmin(adminId);
        ReviewStatus to = request.reviewStatus();
        List<PolicyDocument> versions = lockVersions(policyDocumentId, to);
        PolicyDocument doc = versions.stream().filter(d -> d.getId().equals(policyDocumentId)).findFirst()
                .orElseThrow(() -> notFound(policyDocumentId));
        if (!doc.getReviewStatus().canMoveTo(to)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "review status cannot change",
                    Map.of("reason", "INVALID_TRANSITION", "from", doc.getReviewStatus(), "to", to));
        }
        LocalDate effectiveFrom = request.effectiveFrom() != null ? request.effectiveFrom() : doc.getEffectiveFrom();
        LocalDate effectiveTo = request.effectiveTo() != null ? request.effectiveTo() : doc.getEffectiveTo();
        // ck_policy_documents_period
        if (effectiveFrom != null && effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("fields",
                    List.of(Map.of("field", "effectiveTo", "reason", "must be after effectiveFrom"))));
        }

        List<PolicyDocument> previous = to != ReviewStatus.APPROVED ? List.of() : versions.stream()
                .filter(d -> d != doc && d.getReviewStatus() == ReviewStatus.APPROVED).toList();
        // 시행일이 더 늦은 버전이 이미 승인돼 있으면 어느 쪽을 쓸지 사람이 정해야 한다(그 버전을 먼저 정리)
        for (PolicyDocument old : previous) {
            if (effectiveFrom != null && old.getEffectiveFrom() != null && effectiveFrom.isBefore(old.getEffectiveFrom())) {
                throw new BusinessException(ErrorCode.INVALID_STATE, "a later version is already approved",
                        Map.of("reason", "NEWER_VERSION_APPROVED", "policyDocumentId", old.getId()));
            }
        }

        LocalDate today = LocalDate.now(clock);
        List<Change> changed = new ArrayList<>();
        Before before = Before.of(doc);
        doc.review(to, effectiveFrom, effectiveTo);
        changed.add(record(doc, before, "REVIEW", null, adminId, request.note(), today));
        for (PolicyDocument old : previous) {
            Before oldBefore = Before.of(old);
            boolean startsLater = effectiveFrom != null
                    && (old.getEffectiveFrom() == null || effectiveFrom.isAfter(old.getEffectiveFrom()));
            if (startsLater) {
                // 새 시행일 전에 이미 끝나는 버전은 겹치지 않으므로 그대로 둔다
                if (old.getEffectiveTo() != null && !old.getEffectiveTo().isAfter(effectiveFrom)) {
                    continue;
                }
                old.endBefore(effectiveFrom);
            } else {
                // 시행일이 없거나 같으면 기간을 나눌 수 없다(effective_to 를 같은 날로 채우면 ck 에 걸림)
                old.retire();
            }
            changed.add(record(old, oldBefore, "SUPERSEDED", doc.getId(), adminId, null, today));
        }
        List<String> warnings = to == ReviewStatus.APPROVED && doc.getChunkCount() == null
                ? List.of(NOT_INGESTED) : List.of();
        return new PolicyDocumentReviewResponse(changed, warnings);
    }

    /**
     * 승인이면 같은 문서(source_type·external_id)의 모든 버전을 id 순으로 한 번에 잠근다. 대상만 먼저 잠그고 나머지를
     * 나중에 잠그면 두 버전을 동시에 승인할 때 서로를 기다린다. external_id 가 없는 문서(비자 원문 seed 등)는 묶지 않고
     * 대상만 잠근다. 묶음 키(source_type·external_id)는 바뀌지 않는 값이라 잠그기 전에 읽어도 된다.
     */
    private List<PolicyDocument> lockVersions(Long id, ReviewStatus to) {
        if (to == ReviewStatus.APPROVED) {
            List<Map<String, Object>> key = jdbcTemplate.queryForList(
                    "SELECT source_type, external_id FROM policy_documents WHERE id = ?", id);
            if (key.isEmpty()) {
                throw notFound(id);
            }
            String externalId = (String) key.get(0).get("external_id");
            if (externalId != null) {
                return policyDocumentRepository.findVersionsForUpdate(
                        PolicySourceType.valueOf((String) key.get(0).get("source_type")), externalId);
            }
        }
        return policyDocumentRepository.findByIdForUpdate(id).map(List::of).orElseThrow(() -> notFound(id));
    }

    private static BusinessException notFound(Long id) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("policyDocumentId", id));
    }

    private void requireAdmin(Long userId) {
        boolean admin = userRepository.findById(userId).map(user -> user.getRole() == UserRole.ADMIN).orElse(false);
        if (!admin) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private Change record(PolicyDocument doc, Before before, String reason, Long supersededBy, Long adminId,
                          String note, LocalDate today) {
        jdbcTemplate.update(INSERT_REVIEW, doc.getId(), reason, before.status().name(), doc.getReviewStatus().name(),
                before.effectiveFrom(), before.effectiveTo(), doc.getEffectiveFrom(), doc.getEffectiveTo(),
                supersededBy, adminId, note);
        return new Change(doc.getId(), doc.getPolicyVersion(), before.status(), doc.getReviewStatus(),
                doc.getEffectiveFrom(), doc.getEffectiveTo(), doc.isSearchable(today), reason);
    }

    /** 바꾸기 전 값(이력의 from_*). */
    private record Before(ReviewStatus status, LocalDate effectiveFrom, LocalDate effectiveTo) {

        static Before of(PolicyDocument doc) {
            return new Before(doc.getReviewStatus(), doc.getEffectiveFrom(), doc.getEffectiveTo());
        }
    }
}
