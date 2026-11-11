package com.hanstep.chat.domain;

/** chat_messages.status. STREAMING 은 답변 생성 중, PARTIAL 은 앱 연결이 끊겨 중단된 답변. */
public enum ChatMessageStatus {
    COMPLETED,
    STREAMING,
    PARTIAL,
    FAILED
}
