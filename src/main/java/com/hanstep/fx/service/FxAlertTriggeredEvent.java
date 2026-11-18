package com.hanstep.fx.service;

/**
 * 도달 판정이 알림 1건을 TRIGGERED 로 바꾼 직후 발행한다(FxAlertTriggerService). 커밋된 뒤 FxAlertStreamService 가 받아
 * 그 사용자의 열린 스트림으로 보낸다. 판정에 트랜잭션이 없으면(지금의 수집기·다시 판정) UPDATE 가 이미 커밋된 상태다.
 */
public record FxAlertTriggeredEvent(long alertId) {
}
