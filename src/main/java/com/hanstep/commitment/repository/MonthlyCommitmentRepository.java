package com.hanstep.commitment.repository;

import com.hanstep.commitment.domain.MonthlyCommitment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyCommitmentRepository extends JpaRepository<MonthlyCommitment, Long> {

    List<MonthlyCommitment> findAllByUserIdAndActiveTrueOrderById(Long userId);
}
