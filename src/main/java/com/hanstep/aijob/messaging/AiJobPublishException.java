package com.hanstep.aijob.messaging;

/** 브로커가 메시지를 받았다고 확인하지 못함. detail 에는 메시지 본문·접속 정보를 넣지 않는다. */
public class AiJobPublishException extends RuntimeException {

    public enum Reason {
        /** 연결·채널 오류로 보내지 못함 */
        SEND_FAILED,
        /** 브로커가 NACK(채널이 닫혀 confirm 을 못 받은 경우 포함) */
        NACK,
        /** mandatory 반환(NO_ROUTE 등). 큐에 들어가지 않음 */
        RETURNED,
        /** confirm-timeout 안에 ACK·NACK 이 오지 않음 */
        CONFIRM_TIMEOUT,
        INTERRUPTED
    }

    private final Reason reason;

    public AiJobPublishException(Reason reason, String detail, Throwable cause) {
        super(detail == null ? reason.name() : reason.name() + ": " + detail, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
