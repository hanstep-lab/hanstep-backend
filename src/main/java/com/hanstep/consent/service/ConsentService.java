package com.hanstep.consent.service;

import com.hanstep.consent.domain.ConsentStatus;
import com.hanstep.consent.domain.ConsentType;
import com.hanstep.consent.domain.UserConsent;
import com.hanstep.consent.dto.ConsentListResponse;
import com.hanstep.consent.dto.ConsentResponse;
import com.hanstep.consent.dto.ConsentSaveRequest;
import com.hanstep.consent.repository.UserConsentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CNS-01 ~ 02. */
@Service
@Transactional(readOnly = true)
public class ConsentService {

    private final UserConsentRepository consentRepository;

    public ConsentService(UserConsentRepository consentRepository) {
        this.consentRepository = consentRepository;
    }

    /** CNS-01. 종류별 최신 행. */
    public ConsentListResponse getConsents(Long userId) {
        return toResponse(userId, Set.of(ConsentType.values()));
    }

    /** CNS-02. granted=true 는 새 GRANTED 행, false 는 기존 GRANTED 행을 REVOKED 로(삭제 안 함). */
    @Transactional
    public ConsentListResponse saveConsents(Long userId, ConsentSaveRequest request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (ConsentSaveRequest.Item item : request.consents()) {
            if (item.granted()) {
                consentRepository.save(new UserConsent(userId, item.consentType(), item.policyVersion(), now));
            } else {
                consentRepository.findAllByUserIdAndConsentTypeAndStatus(
                        userId, item.consentType(), ConsentStatus.GRANTED).forEach(c -> c.revoke(now));
            }
        }
        Set<ConsentType> saved = request.consents().stream()
                .map(ConsentSaveRequest.Item::consentType)
                .collect(Collectors.toSet());
        return toResponse(userId, saved);
    }

    private ConsentListResponse toResponse(Long userId, Set<ConsentType> types) {
        Map<ConsentType, UserConsent> latest = new EnumMap<>(ConsentType.class);
        for (UserConsent consent : consentRepository.findAllByUserIdOrderByIdDesc(userId)) {
            if (types.contains(consent.getConsentType())) {
                latest.putIfAbsent(consent.getConsentType(), consent);
            }
        }
        return new ConsentListResponse(latest.values().stream().map(ConsentResponse::from).toList());
    }
}
