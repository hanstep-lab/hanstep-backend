package com.hanstep.chat.domain;

import java.util.Arrays;
import java.util.Optional;

/** chat_message_sources.source_type(스키마 ENUM 과 같은 값). */
public enum ChatSourceType {
    POLICY_DOCUMENT,
    PRODUCT_DOCUMENT,
    FINANCIAL_ANALYSIS,
    E74_ASSESSMENT,
    VISA_ROADMAP;

    /** 스키마에 없는 값이면 빈 값. */
    public static Optional<ChatSourceType> find(String value) {
        return Arrays.stream(values()).filter(t -> t.name().equals(value)).findFirst();
    }
}
