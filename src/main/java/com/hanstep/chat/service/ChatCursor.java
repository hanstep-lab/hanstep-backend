package com.hanstep.chat.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** 키셋 커서. 앱에는 불투명 문자열(base64url "정렬시각ms:id")로만 보인다. */
record ChatCursor(Instant at, long id) {

    String encode() {
        String raw = at.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 비었으면 null(첫 페이지). 형식이 틀리면 400. */
    static ChatCursor decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            return new ChatCursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, colon))),
                    Long.parseLong(raw.substring(colon + 1)));
        } catch (RuntimeException e) {
            throw invalid("cursor", "invalid cursor");
        }
    }

    /** size 가 1~max 가 아니면 400. */
    static int checkSize(int size, int max) {
        if (size < 1 || size > max) {
            throw invalid("size", "must be between 1 and " + max);
        }
        return size;
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
