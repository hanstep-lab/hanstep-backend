package com.hanstep.mydata.repository;

import com.hanstep.mydata.domain.Institution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstitutionRepository extends JpaRepository<Institution, Long> {
}
