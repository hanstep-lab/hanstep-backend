package com.hanstep.fx.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 목표 환율 도달 스트림의 event: ready data. FxAlertOpenApiCustomizer 가 Schemas 에 등록한다. */
@Schema(name = "FxAlertStreamReadyEvent", description = "event: ready. 연결하자마자 한 번 온다. 이어서 아직 알리지 않은 도달이 "
        + "event: fx-alert-triggered(FxAlertNotification)로 온다")
public record FxAlertStreamReady(
        @Schema(description = "keep-alive 주석(: ping) 간격(초, 설정값). 이 시간의 2배가 넘도록 아무것도 오지 않으면 끊긴 것으로 보고 "
                + "다시 연결하면 된다", example = "25")
        long pingIntervalSeconds
) {
}
