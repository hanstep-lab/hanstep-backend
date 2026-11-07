package com.hanstep.consent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * user_consents 테이블. 동의할 때마다 새 행, 철회는 그 행을 REVOKED 로 바꾼다(삭제하지 않음).
 * 시각 칸은 UTC(hibernate.jdbc.time_zone).
 */
@Entity
@Table(name = "user_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, columnDefinition = "enum('TERMS','PRIVACY','ID_DOCUMENT',"
            + "'MYDATA','MYDATA_TERMS','AI_CHAT','CREDIT_EVALUATION','MARKETING')")
    private ConsentType consentType;

    @Column(name = "policy_version", nullable = false, length = 40)
    private String policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "enum('GRANTED','REVOKED')")
    private ConsentStatus status;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public UserConsent(Long userId, ConsentType consentType, String policyVersion, Instant grantedAt) {
        this.userId = userId;
        this.consentType = consentType;
        this.policyVersion = policyVersion;
        this.status = ConsentStatus.GRANTED;
        this.grantedAt = grantedAt;
    }

    public void revoke(Instant at) {
        this.status = ConsentStatus.REVOKED;
        this.revokedAt = at;
    }
}
