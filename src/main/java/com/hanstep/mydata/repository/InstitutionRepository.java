package com.hanstep.mydata.repository;

import com.hanstep.mydata.domain.Institution;
import com.hanstep.mydata.domain.InstitutionType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstitutionRepository extends JpaRepository<Institution, Long> {

    List<Institution> findAllByInstitutionTypeInOrderById(Collection<InstitutionType> types);
}
