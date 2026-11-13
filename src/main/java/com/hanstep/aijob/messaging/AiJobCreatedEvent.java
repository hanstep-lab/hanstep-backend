package com.hanstep.aijob.messaging;

/** 작업 저장 트랜잭션 안에서 낸다. 커밋되면 AiJobPublishListener 가 message 를 발행한다(DB 를 다시 읽지 않음). */
public record AiJobCreatedEvent(AiJobMessage message) {
}
