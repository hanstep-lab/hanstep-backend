package com.hanstep.document.service;

import com.hanstep.common.time.KstDates;
import com.hanstep.document.domain.ArcFieldKey;
import com.hanstep.document.dto.DocumentFieldsUpdateRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 등록증 항목 값 검사. DOC-03 수정·DOC-04 확정·체류 정보 직접 저장(PUT /api/v1/visa/profile)이 같은 규칙을 쓴다.
 * 이유 문구에 값은 넣지 않는다.
 */
@Component
public class ArcFieldValidator {

    /** visa_profiles 에 넣는 항목. 확정·직접 저장할 때 모두 값이 있어야 한다. */
    public static final List<ArcFieldKey> PROFILE_KEYS = List.of(ArcFieldKey.NATIONALITY, ArcFieldKey.BIRTH_DATE,
            ArcFieldKey.VISA_TYPE, ArcFieldKey.STAY_EXPIRY);

    private static final Pattern ALPHA3 = Pattern.compile("^[A-Z]{3}$");

    private final JdbcTemplate jdbc;

    public ArcFieldValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * fields[{fieldKey, value}] 검사. 모르는·중복 fieldKey, keyRejection 이 거절한 키, 형식 오류를 모두 모은다(경로 fields[i].…).
     *
     * @param keyRejection 받지 않는 키면 이유, 받으면 null
     */
    public List<Map<String, String>> checkItems(List<DocumentFieldsUpdateRequest.Item> items,
                                                Function<ArcFieldKey, String> keyRejection) {
        List<Map<String, String>> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            DocumentFieldsUpdateRequest.Item item = items.get(i);
            String path = "fields[" + i + "]";
            ArcFieldKey key = ArcFieldKey.fromKey(item.fieldKey());
            String rejection = key == null ? "unknown fieldKey" : keyRejection.apply(key);
            if (rejection != null) {
                errors.add(error(path + ".fieldKey", rejection));
            } else if (!seen.add(key.key())) {
                errors.add(error(path + ".fieldKey", "duplicate fieldKey"));
            } else {
                String reason = invalidReason(key, item.value().trim());
                if (reason != null) {
                    errors.add(error(path + ".value", reason));
                }
            }
        }
        return errors;
    }

    /** 형식 검사. 맞으면 null. */
    public String invalidReason(ArcFieldKey key, String value) {
        return switch (key) {
            case NATIONALITY -> ALPHA3.matcher(value).matches() ? null : "must be ISO 3166-1 alpha-3 (e.g. PHL)";
            case BIRTH_DATE -> {
                LocalDate date = parseDate(value);
                yield date == null ? "must be YYYY-MM-DD"
                        : date.isAfter(LocalDate.now(KstDates.KST)) ? "must not be in the future" : null;
            }
            case STAY_EXPIRY -> parseDate(value) == null ? "must be YYYY-MM-DD" : null;
            case VISA_TYPE -> visaTypeIdOrNull(value) == null ? "unknown visa type" : null;
            case NAME_EN, REGISTRATION_NO -> null;
        };
    }

    /** YYYY-MM-DD 가 아니면 null. */
    public static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** visa_types.code 의 id. 없으면 null. */
    public Long visaTypeIdOrNull(String code) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM visa_types WHERE code = ?", Long.class, code);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public static Map<String, String> error(String field, String reason) {
        return Map.of("field", field, "reason", reason);
    }
}
