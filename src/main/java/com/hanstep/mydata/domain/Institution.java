package com.hanstep.mydata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** institutions 테이블(기준표, 시드로만 채움). 읽기 전용. */
@Entity
@Table(name = "institutions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Institution {

    @Id
    private Long id;

    @Column(name = "institution_code", nullable = false, length = 50)
    private String institutionCode;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "name_ko", length = 100)
    private String nameKo;

    @Enumerated(EnumType.STRING)
    @Column(name = "institution_type", nullable = false,
            columnDefinition = "enum('BANK','CARD','TELECOM','BILLER','PUBLIC','OTHER')")
    private InstitutionType institutionType;
}
