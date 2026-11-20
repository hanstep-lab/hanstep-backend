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
 * 지우는 것: onboarding_progress, documents(document_fields 는 CASCADE), 그 사용자의 OCR 작업(ai_jobs), 확정 전 문서의 R2 원본,
 * visa_roadmaps(visa_roadmap_steps 는 CASCADE), e74_assessments(e74_assessment_items 는 CASCADE).
 * 바꾸는 것: ACTIVE 목표 환율 알림과 ACTIVE 금융 목표(온보딩 목표 선택)는 CANCELLED 로, 매달 나가는 돈(온보딩 맞춤 질문)은
 * is_active=false 로(행은 남김).
 * Reyes 는 visa_profiles 의 등록증 항목과 users.preferred_language 를 데모 시드 값으로 되돌린다.
 * 남기는 것: 다른 데모 계정의 visa_profiles(source_document_id 는 SET NULL), 입국일·이전 체류 개월, 마이데이터·금융 분석 등.
 */
@Service
public class DemoResetService {

    /** 데모 계정의 external_user_key 접두사(03 데모 시드 demo-e9-ph-reyes). */
    static final String DEMO_KEY_PREFIX = "demo-";

    /**
     * Reyes 의 visa_profiles 데모 시드 값. db/init/03_hanstep_demo_seed.sql 의 visa_profiles INSERT 와 같게 둔다.
     * DOC-04 확정이 등록증 값으로 덮어쓴 체류자격·만료일·국적·생년월일을 되돌린다(입국일·이전 체류 개월은 그대로).
     */
    public static final class ReyesSeed {
        public static final String EXTERNAL_USER_KEY = "demo-e9-ph-reyes";
        public static final String VISA_TYPE_CODE = "E-9";
        public static final String STAY_EXPIRES_ON = "2027-10-04";
        public static final String NATIONALITY_CODE = "PHL";
        public static final String BIRTH_DATE = "1995-08-15";
        public static final String EVIDENCE_STATUS = "VERIFIED";
        /** users.preferred_language(같은 시드 파일의 users INSERT). 온보딩 언어 화면이 바꾼 값을 되돌린다(시연은 en) */
        public static final String PREFERRED_LANGUAGE = "en";

        private ReyesSeed() {
        }
    }

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
                        + "originalsDeleted={}, originalsFailed={}, alertsCancelled={}, profileRestored={}, "
                        + "languageRestored={}, e74Assessments={}, roadmaps={}, goalsCancelled={}, "
                        + "commitmentsDeactivated={}", userId,
                deleted.documents(), deleted.documentFields(), deleted.ocrJobs(), deleted.onboardingSteps(),
                originalsDeleted, originalsFailed, deleted.alertsCancelled(), deleted.profileRestored(),
                deleted.languageRestored(), deleted.e74Assessments(), deleted.roadmaps(), deleted.goalsCancelled(),
                deleted.commitmentsDeactivated());
        return new DemoResetResponse(deleted.documents(), deleted.documentFields(), deleted.ocrJobs(),
                deleted.onboardingSteps(), originalsDeleted, originalsFailed, deleted.alertsCancelled(),
                deleted.profileRestored(), deleted.e74Assessments(), deleted.roadmaps(), deleted.languageRestored(),
                deleted.goalsCancelled(), deleted.commitmentsDeactivated());
    }

    private Deleted deleteRows(Long userId) {
        // 다른 초기화·업로드와 겹치지 않게 사용자 행을 잠근다
        List<String> keys = jdbc.queryForList("SELECT external_user_key FROM users WHERE id = ? FOR UPDATE",
                String.class, userId);
        if (keys.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String externalUserKey = keys.getFirst();
        if (!externalUserKey.startsWith(DEMO_KEY_PREFIX)) {
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
        // 온보딩 목표 선택 화면이 GOL-01 의 ACTIVE 목표를 미리 체크하므로 해제한다. GOL-02 처럼 지우지 않고 CANCELLED(ACHIEVED 는 그대로)
        int goalsCancelled = jdbc.update(
                "UPDATE financial_goals SET status = 'CANCELLED' WHERE user_id = ? AND status = 'ACTIVE'", userId);
        // 맞춤 질문(고정생활비·송금·변동지출) 입력값. CMT-02 처럼 지우지 않고 비활성으로 둔다
        int commitmentsDeactivated = jdbc.update(
                "UPDATE monthly_commitments SET is_active = FALSE WHERE user_id = ? AND is_active", userId);
        // 같은 조건으로 다시 만들 수 있게(uq_fx_alerts_active). 지우지 않고 사용자가 지운 것과 같은 CANCELLED 로 둔다
        int alertsCancelled = jdbc.update(
                "UPDATE fx_alerts SET status = 'CANCELLED' WHERE user_id = ? AND status = 'ACTIVE'", userId);
        boolean reyes = ReyesSeed.EXTERNAL_USER_KEY.equals(externalUserKey);
        boolean profileRestored = reyes && restoreReyesProfile(userId);
        boolean languageRestored = reyes && jdbc.update("UPDATE users SET preferred_language = ? WHERE id = ?",
                ReyesSeed.PREFERRED_LANGUAGE, userId) > 0;
        // 로드맵을 먼저 지운다(visa_roadmap_steps 는 CASCADE). 평가를 지워도 로드맵이 CASCADE 로 지워지지만 개수를 따로 센다.
        // 평가를 지우면 e74_assessment_items 는 CASCADE. chat_tool_calls.result_ref_id 는 FK 가 없어 그대로 남는다
        int roadmaps = jdbc.update("DELETE FROM visa_roadmaps WHERE user_id = ?", userId);
        int e74Assessments = jdbc.update("DELETE FROM e74_assessments WHERE user_id = ?", userId);
        return new Deleted(documents, documentFields, ocrJobs, onboardingSteps, alertsCancelled, profileRestored,
                languageRestored, e74Assessments, roadmaps, goalsCancelled, commitmentsDeactivated, originals);
    }

    /** 데모 시드 값으로 되돌린다. 프로필 행이 없으면 만들지 않고 false(입국일·이전 체류 개월을 정할 수 없음). */
    private boolean restoreReyesProfile(Long userId) {
        int rows = jdbc.update("""
                UPDATE visa_profiles
                SET visa_type_id = (SELECT id FROM visa_types WHERE code = ?), stay_expires_on = ?,
                  nationality_code = ?, birth_date = ?, source_document_id = NULL, evidence_status = ?
                WHERE user_id = ?""", ReyesSeed.VISA_TYPE_CODE, ReyesSeed.STAY_EXPIRES_ON,
                ReyesSeed.NATIONALITY_CODE, ReyesSeed.BIRTH_DATE, ReyesSeed.EVIDENCE_STATUS, userId);
        return rows > 0;
    }

    private record Original(long documentId, String objectKey) {
    }

    private record Deleted(int documents, int documentFields, int ocrJobs, int onboardingSteps,
                           int alertsCancelled, boolean profileRestored, boolean languageRestored,
                           int e74Assessments, int roadmaps, int goalsCancelled, int commitmentsDeactivated,
                           List<Original> originals) {
    }
}
