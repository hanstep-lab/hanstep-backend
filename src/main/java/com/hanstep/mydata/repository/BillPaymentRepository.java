package com.hanstep.mydata.repository;

import com.hanstep.mydata.domain.BillPayment;
import com.hanstep.mydata.domain.BillType;
import java.time.LocalDate;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillPaymentRepository extends JpaRepository<BillPayment, Long> {

    /**
     * 청구 월이 from~to 인 types 청구 중 연체가 한 번이라도 있었던 달 수.
     * 연체 = 기한보다 늦게 냄, 또는 기한(today 전)이 지났는데 미납(임시 기준). 같은 달 여러 건이 늦어도 1로 센다.
     */
    @Query("select count(distinct b.billingMonth) from BillPayment b where b.userId = :userId "
            + "and b.billType in :types and b.billingMonth between :from and :to "
            + "and (b.paidOn > b.dueOn or (b.paidOn is null and b.dueOn < :today))")
    long countLateMonths(@Param("userId") Long userId, @Param("types") Collection<BillType> types,
                         @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("today") LocalDate today);
}
