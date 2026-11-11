package com.hanstep.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * policy_documents 테이블. 비자 정책 원문 메타의 기준 데이터(청크·벡터는 MongoDB, policy_document_id = id).
 * 버전마다 새 행(content_hash UNIQUE). 시각 칸은 UTC.
 */
@Entity
@Table(name = "policy_documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PolicyDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false,
            columnDefinition = "enum('LAW','ADMIN_RULE','NOTICE','GUIDE','SCORE_TABLE')")
    private PolicySourceType sourceType;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "issuing_agency", nullable = false, length = 100)
    private String issuingAgency;

    @Column(name = "source_url", nullable = false, length = 1000)
    private String sourceUrl;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "visa_type_code", length = 20)
    private String visaTypeCode;

    @Column(name = "published_on")
    private LocalDate publishedOn;

    /** NULL = 원문에 시행일 표기 없음. */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /** 종료일(미포함). */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "policy_version", nullable = false, length = 40)
    private String policyVersion;

    @Column(name = "content_hash", nullable = false, columnDefinition = "char(64)")
    private String contentHash;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false,
            columnDefinition = "enum('DRAFT','APPROVED','NEEDS_REVIEW','RETIRED')")
    private ReviewStatus reviewStatus;

    /** MongoDB 컬렉션 이름(컬럼명은 이전 Chroma 기준). */
    @Column(name = "chroma_collection", length = 100)
    private String chromaCollection;

    @Column(name = "chunk_count", columnDefinition = "int unsigned")
    private Integer chunkCount;

    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    @Column(name = "ingested_at")
    private Instant ingestedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * 검색 가능 여부. APPROVED 이고 today(KST)가 시행 기간 안(effective_to 미포함).
     * effective_from 이 NULL 이면 시행 시작 조건은 건너뛴다(비자 원문 대부분이 시행일 표기 없음, MVP 결정).
     */
    public boolean isSearchable(LocalDate today) {
        if (reviewStatus != ReviewStatus.APPROVED) {
            return false;
        }
        if (effectiveFrom != null && today.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || today.isBefore(effectiveTo);
    }

    /** INT-13 적재 결과 기록. review_status 는 바꾸지 않는다(인덱싱 완료 ≠ 승인). object_key 는 비어 있을 때만 채운다. */
    public void recordIngestion(int chunkCount, String collection, String embeddingModel,
                                String objectKey, Instant ingestedAt) {
        this.chunkCount = chunkCount;
        this.chromaCollection = collection;
        this.embeddingModel = embeddingModel;
        this.ingestedAt = ingestedAt;
        if (this.objectKey == null && objectKey != null) {
            this.objectKey = objectKey;
        }
    }
}
