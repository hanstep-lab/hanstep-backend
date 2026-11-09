package com.hanstep.mydata.repository;

import com.hanstep.mydata.domain.DataConnection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataConnectionRepository extends JpaRepository<DataConnection, Long> {

    Optional<DataConnection> findByUserIdAndInstitutionId(Long userId, Long institutionId);

    List<DataConnection> findAllByUserIdOrderById(Long userId);
}
