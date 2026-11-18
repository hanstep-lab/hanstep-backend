package com.hanstep.visa.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.document.domain.ArcFieldKey;
import com.hanstep.document.dto.DocumentFieldsUpdateRequest;
import com.hanstep.document.service.ArcFieldValidator;
import com.hanstep.onboarding.domain.OnboardingStep;
import com.hanstep.onboarding.domain.OnboardingStepStatus;
import com.hanstep.onboarding.service.OnboardingService;
import com.hanstep.visa.dto.VisaProfileResponse;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 체류 정보 직접 저장(VIS-02 보완, #131). OCR 이 실패하거나 오래 걸릴 때 사용자가 등록증 앞면 항목을 직접 입력한다.
 * 값 검사는 DOC-03·DOC-04 와 같다(ArcFieldValidator). 값은 로그·오류 응답에 싣지 않는다.
 */
@Service
public class VisaProfileService {

    static final String DECLARED = "DECLARED";

    private final ArcFieldValidator validator;
    private final OnboardingService onboardingService;
    private final JdbcTemplate jdbc;

    public VisaProfileService(ArcFieldValidator validator, OnboardingService onboardingService, JdbcTemplate jdbc) {
        this.validator = validator;
        this.onboardingService = onboardingService;
        this.jdbc = jdbc;
    }

    /**
     * nationality·birthDate·visaType·stayExpiry 네 항목 모두 필수. 오류는 모두 모아 400.
     * visa_profiles UPSERT 는 이 4개와 근거(source_document_id NULL, DECLARED)만 바꾸고 입국일·이전 체류 개월은 그대로 둔다.
     * 온보딩 ID_DOCUMENT 단계를 COMPLETED 로 기록한다.
     */
    @Transactional
    public VisaProfileResponse save(Long userId, DocumentFieldsUpdateRequest request) {
        List<Map<String, String>> errors = validator.checkItems(request.fields(),
                key -> ArcFieldValidator.PROFILE_KEYS.contains(key) ? null : "not a visa profile field");
        Map<ArcFieldKey, String> values = new EnumMap<>(ArcFieldKey.class);
        for (DocumentFieldsUpdateRequest.Item item : request.fields()) {
            ArcFieldKey key = ArcFieldKey.fromKey(item.fieldKey());
            if (key != null) {
                values.putIfAbsent(key, item.value().trim());
            }
        }
        for (ArcFieldKey key : ArcFieldValidator.PROFILE_KEYS) {
            if (!values.containsKey(key)) {
                errors.add(ArcFieldValidator.error(key.key(), "required"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("fields", errors));
        }

        String nationality = values.get(ArcFieldKey.NATIONALITY);
        LocalDate birthDate = LocalDate.parse(values.get(ArcFieldKey.BIRTH_DATE));
        String visaType = values.get(ArcFieldKey.VISA_TYPE);
        LocalDate stayExpiry = LocalDate.parse(values.get(ArcFieldKey.STAY_EXPIRY));
        // 사용자당 1행(uq_visa_profiles_user). current_stay_started_on·prior_stay_months 는 넣지도 바꾸지도 않는다
        jdbc.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, nationality_code, birth_date,
                  source_document_id, evidence_status)
                VALUES (?, ?, ?, ?, ?, NULL, ?) AS new
                ON DUPLICATE KEY UPDATE visa_type_id = new.visa_type_id, stay_expires_on = new.stay_expires_on,
                  nationality_code = new.nationality_code, birth_date = new.birth_date,
                  source_document_id = NULL, evidence_status = new.evidence_status
                """, userId, validator.visaTypeIdOrNull(visaType), stayExpiry, nationality, birthDate, DECLARED);
        onboardingService.finishStep(userId, OnboardingStep.ID_DOCUMENT, OnboardingStepStatus.COMPLETED);

        return new VisaProfileResponse(nationality, birthDate, visaType, stayExpiry,
                ChronoUnit.DAYS.between(LocalDate.now(KstDates.KST), stayExpiry), DECLARED);
    }
}
