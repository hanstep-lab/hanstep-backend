package com.hanstep.consent.repository;

import com.hanstep.consent.domain.ConsentStatus;
import com.hanstep.consent.domain.ConsentType;
import com.hanstep.consent.domain.UserConsent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    /** 최신 행이 먼저. 종류별 최신 1건은 서비스에서 선택. */
    List<UserConsent> findAllByUserIdOrderByIdDesc(Long userId);

    List<UserConsent> findAllByUserIdAndConsentTypeAndStatus(Long userId, ConsentType consentType,
                                                             ConsentStatus status);
}
