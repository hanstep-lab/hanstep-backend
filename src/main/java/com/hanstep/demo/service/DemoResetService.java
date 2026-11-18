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
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 계정 초기화(#131). 테스트·시연 리허설 때 온보딩·등록증 촬영을 처음부터 다시 할 수 있게 한다.
 * 지우는 것: onboarding_progress, documents(document_fields 는 CASCADE), 확정 전 문서의 R2 원본.
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

    public DemoResetService(@Value("${hanstep.demo.reset-enabled:false}") boolean enabled, ArcImageStorage storage,
                            JdbcTemplate jdbc) {
        this.enabled = enabled;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    /**
     * 요청한 사용자 본인만. 꺼져 있으면 404, 데모 계정이 아니면 403.
     * 한 트랜잭션: 행 삭제 → R2 원본 삭제. R2 삭제가 실패하면 롤백되어 아무것도 지워지지 않은 상태로 남는다(다시 부르면 된다).
     */
    @Transactional
    public DemoResetResponse reset(Long userId) {
        if (!enabled) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        // 다른 초기화·업로드와 겹치지 않게 사용자 행을 잠근다
        List<String> keys = jdbc.queryForList("SELECT external_user_key FROM users WHERE id = ? FOR UPDATE",
                String.class, userId);
        if (keys.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!keys.getFirst().startsWith(DEMO_KEY_PREFIX)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "demo account only", null);
        }

        List<String> originals = jdbc.queryForList(
                "SELECT object_key FROM documents WHERE user_id = ? AND object_key IS NOT NULL ORDER BY id",
                String.class, userId);
        int documentFields = jdbc.queryForObject("""
                SELECT COUNT(*) FROM document_fields f JOIN documents d ON d.id = f.document_id
                WHERE d.user_id = ?""", Integer.class, userId);
        int documents = jdbc.update("DELETE FROM documents WHERE user_id = ?", userId);
        int onboardingSteps = jdbc.update("DELETE FROM onboarding_progress WHERE user_id = ?", userId);
        originals.forEach(storage::delete);

        log.info("demo reset: userId={}, documents={}, documentFields={}, originals={}, onboardingSteps={}",
                userId, documents, documentFields, originals.size(), onboardingSteps);
        return new DemoResetResponse(documents, documentFields, originals.size(), onboardingSteps);
    }
}
