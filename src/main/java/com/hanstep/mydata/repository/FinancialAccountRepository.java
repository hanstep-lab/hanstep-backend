package com.hanstep.mydata.repository;

import com.hanstep.mydata.domain.FinancialAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, Long> {

    Optional<FinancialAccount> findByUserIdAndInstitutionIdAndExternalAccountKey(Long userId, Long institutionId,
                                                                                 String externalAccountKey);

    long countByUserIdAndInstitutionId(Long userId, Long institutionId);
}
