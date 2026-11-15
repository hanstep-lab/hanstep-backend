package com.hanstep.policy.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.policy.domain.PolicyDocument;
import com.hanstep.policy.domain.PolicySourceType;
import com.hanstep.policy.dto.LawDocumentRegisterRequest;
import com.hanstep.policy.dto.PolicyDocumentRef;
import com.hanstep.policy.repository.PolicyDocumentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * AI-R6 법제처 법령 문서 등록. content_hash UNIQUE 가 멱등 기준(같은 해시면 기존 행).
 * 새 문서는 POLICY_INGEST 작업과 함께 저장하고 커밋 후 발행한다(LawDocumentWriter). 이미 있는 문서면 작업을 새로 만들지 않는다.
 * 동시 요청은 UNIQUE 위반을 잡아 먼저 들어간 행을 돌려주므로, 이 메서드는 트랜잭션 밖에 두고 저장 트랜잭션만 짧게 둔다.
 */
@Service
public class LawDocumentRegisterService {

    private static final Logger log = LoggerFactory.getLogger(LawDocumentRegisterService.class);
    private static final DateTimeFormatter VERSION_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int VERSION_HASH_SUFFIX = 6;

    private final PolicyDocumentRepository policyDocumentRepository;
    private final LawDocumentWriter lawDocumentWriter;
    private final Clock clock;

    public LawDocumentRegisterService(PolicyDocumentRepository policyDocumentRepository,
                                      LawDocumentWriter lawDocumentWriter) {
        this.policyDocumentRepository = policyDocumentRepository;
        this.lawDocumentWriter = lawDocumentWriter;
        this.clock = Clock.system(KstDates.KST);
    }

    public record Result(PolicyDocumentRef document, boolean created) {
    }

    public Result register(String idempotencyKey, LawDocumentRegisterRequest request) {
        if (!request.contentHash().equals(idempotencyKey)) {
            throw validation("Idempotency-Key", "must equal contentHash");
        }
        if (request.sourceType() != PolicySourceType.LAW) {
            throw validation("sourceType", "only LAW is supported");
        }
        var existing = policyDocumentRepository.findByContentHash(request.contentHash());
        if (existing.isPresent()) {
            return new Result(PolicyDocumentRef.of(existing.get()), false);
        }

        PolicyDocument doc = PolicyDocument.registerLaw(request.title(), request.issuingAgency(), request.sourceUrl(),
                request.externalId(), request.visaTypeCode(), request.publishedOn(), request.effectiveFrom(),
                policyVersion(request), request.contentHash(), request.objectKey());
        try {
            PolicyDocument saved = lawDocumentWriter.create(doc);
            log.info("law document registered: id={} version={}", saved.getId(), saved.getPolicyVersion());
            return new Result(PolicyDocumentRef.of(saved), true);
        } catch (DataIntegrityViolationException e) {
            // 같은 해시가 동시에 들어온 경우(문서·작업 모두 롤백, 발행 없음). 먼저 들어간 행을 돌려준다
            PolicyDocument first = policyDocumentRepository.findByContentHash(request.contentHash())
                    .orElseThrow(() -> e);
            return new Result(PolicyDocumentRef.of(first), false);
        }
    }

    /**
     * LAW-{externalId}-{yyyyMMdd}. 날짜는 시행일, 없으면 공포일, 둘 다 없으면 등록일(KST).
     * 같은 법령·같은 날짜인데 해시가 다른 행이 이미 있으면 -{해시 앞 6자}를 붙인다(최대 40자).
     */
    String policyVersion(LawDocumentRegisterRequest request) {
        LocalDate date = request.effectiveFrom() != null ? request.effectiveFrom()
                : request.publishedOn() != null ? request.publishedOn()
                : LocalDate.now(clock);
        String version = "LAW-" + request.externalId() + "-" + date.format(VERSION_DATE);
        if (policyDocumentRepository.existsByPolicyVersion(version)) {
            version += "-" + request.contentHash().substring(0, VERSION_HASH_SUFFIX);
        }
        return version;
    }

    private static BusinessException validation(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
