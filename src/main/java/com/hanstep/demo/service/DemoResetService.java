package com.hanstep.demo.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.demo.dto.DemoResetResponse;
import com.hanstep.document.storage.ArcImageStorage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 데모 계정 초기화(#131). 테스트·시연 리허설 때 온보딩·등록증 촬영을 처음부터 다시 할 수 있게 한다.
 * 지우는 것: onboarding_progress, documents(document_fields 는 CASCADE), 그 사용자의 OCR 작업(ai_jobs), 확정 전 문서의 R2 원본.
 * 남기는 것: visa_profiles(입국일·이전 체류 개월이 함께 있음, source_document_id 는 SET NULL), 목표·마이데이터·금융 분석 등.
 */
@Service
public class DemoResetService {

    /** 데모 계정의 external_user_key 접두사(03 데모 시드 demo-e9-ph-reyes). */
    static final String DEMO_KEY_PREFIX = "demo-";

    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    private final boolean enabled;
    private final ArcImageStorage storage;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DemoResetService(@Value("${hanstep.demo.reset-enabled:false}") boolean enabled, ArcImageStorage storage,
                            JdbcTemplate jdbc, TransactionTemplate tx) {
        this.enabled = enabled;
        this.storage = storage;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * 요청한 사용자 본인만. 꺼져 있으면 404, 데모 계정이 아니면 403.
     * DB 삭제를 한 트랜잭션으로 먼저 커밋하고, 커밋 뒤에 R2 원본을 지운다. R2 는 DB 롤백에 묶이지 않으므로
     * 삭제가 실패해도 200 으로 응답하고 실패 수만 알린다(가리키는 행이 없는 객체로 남음, 경고 로그).
     */
    public DemoResetResponse reset(Long userId) {
        if (!enabled) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Deleted deleted = tx.execute(status -> deleteRows(userId));

        int originalsDeleted = 0;
        int originalsFailed = 0;
        for (Original original : deleted.originals()) {
            try {
                storage.delete(original.objectKey());
                originalsDeleted++;
            } catch (RuntimeException e) {
                originalsFailed++;
                // 객체 키는 남기지 않는다
                log.warn("demo reset: original of document {} was not deleted ({})", original.documentId(),
                        e.getClass().getSimpleName());
            }
        }

        log.info("demo reset: userId={}, documents={}, documentFields={}, ocrJobs={}, onboardingSteps={}, "
                        + "originalsDeleted={}, originalsFailed={}", userId, deleted.documents(),
                deleted.documentFields(), deleted.ocrJobs(), deleted.onboardingSteps(), originalsDeleted,
                originalsFailed);
        return new DemoResetResponse(deleted.documents(), deleted.documentFields(), deleted.ocrJobs(),
                deleted.onboardingSteps(), originalsDeleted, originalsFailed);
    }

    private Deleted deleteRows(Long userId) {
        // 다른 초기화·업로드와 겹치지 않게 사용자 행을 잠근다
        List<String> keys = jdbc.queryForList("SELECT external_user_key FROM users WHERE id = ? FOR UPDATE",
                String.class, userId);
        if (keys.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!keys.getFirst().startsWith(DEMO_KEY_PREFIX)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "demo account only", null);
        }

        List<Original> originals = jdbc.query(
                "SELECT id, object_key FROM documents WHERE user_id = ? AND object_key IS NOT NULL ORDER BY id",
                (rs, i) -> new Original(rs.getLong("id"), rs.getString("object_key")), userId);
        int documentFields = jdbc.queryForObject("""
                SELECT COUNT(*) FROM document_fields f JOIN documents d ON d.id = f.document_id
                WHERE d.user_id = ?""", Integer.class, userId);
        int documents = jdbc.update("DELETE FROM documents WHERE user_id = ?", userId);
        // 지운 문서를 가리키는 OCR 작업. 남기면 주기 재발행이 발행 대기 작업을 계속 다시 보낸다(resource_id 에 FK 없음)
        int ocrJobs = jdbc.update(
                "DELETE FROM ai_jobs WHERE user_id = ? AND job_type = 'OCR' AND resource_type = 'DOCUMENT'", userId);
        int onboardingSteps = jdbc.update("DELETE FROM onboarding_progress WHERE user_id = ?", userId);
        return new Deleted(documents, documentFields, ocrJobs, onboardingSteps, originals);
    }

    private record Original(long documentId, String objectKey) {
    }

    private record Deleted(int documents, int documentFields, int ocrJobs, int onboardingSteps,
                           List<Original> originals) {
    }
}
