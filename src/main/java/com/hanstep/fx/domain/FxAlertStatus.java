package com.hanstep.fx.domain;

/** 알림 상태(fx_alerts.status). 지우면 행은 남기고 CANCELLED. TRIGGERED 는 도달 판정(FxAlertTriggerService)이 바꾼다. */
public enum FxAlertStatus {
    ACTIVE, TRIGGERED, CANCELLED
}
