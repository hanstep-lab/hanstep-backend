package com.hanstep.document.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 외국인등록증 앞면 OCR 항목(document_fields.field_key 값). OCR 결과는 이 6개를 한 번씩 보낸다.
 * stayExpiry 는 앞면에 없어 항상 null(MISSING)로 오고, 사용자가 확인 화면에서 입력한다.
 */
public enum ArcFieldKey {
    NAME_EN("nameEn"),
    NATIONALITY("nationality"),
    BIRTH_DATE("birthDate"),
    VISA_TYPE("visaType"),
    STAY_EXPIRY("stayExpiry"),
    REGISTRATION_NO("registrationNo");

    private static final Map<String, ArcFieldKey> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ArcFieldKey::key, Function.identity()));

    private final String key;

    ArcFieldKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** 모르는 키면 null. */
    public static ArcFieldKey fromKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }
}
