package com.hanstep.mydata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** data_connections 테이블. 사용자·기관당 1행(UNIQUE). 다시 연결하면 같은 행을 갱신한다. */
@Entity
@Table(name = "data_connections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(name = "consent_id", nullable = false)
    private Long consentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false,
            columnDefinition = "enum('PENDING','CONNECTED','EXPIRED','REVOKED','ERROR')")
    private ConnectionStatus status;

    // MydataScope 이름 배열 JSON(예: ["ACCOUNT_BALANCE","TRANSACTIONS"])
    @Column(name = "requested_scopes_json", columnDefinition = "json")
    private String requestedScopesJson;

    @Column(name = "lookback_months", nullable = false, columnDefinition = "smallint unsigned")
    private Integer lookbackMonths;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    public DataConnection(Long userId, Long institutionId) {
        this.userId = userId;
        this.institutionId = institutionId;
    }

    public void connect(Long consentId, String requestedScopesJson, int lookbackMonths, Instant syncedAt) {
        this.consentId = consentId;
        this.status = ConnectionStatus.CONNECTED;
        this.requestedScopesJson = requestedScopesJson;
        this.lookbackMonths = lookbackMonths;
        this.lastSyncedAt = syncedAt;
        this.errorCode = null;
    }
}
