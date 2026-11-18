package com.hanstep.fx.config;

import com.hanstep.fx.dto.FxAlertNotification;
import com.hanstep.fx.dto.FxAlertStreamReady;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * 목표 환율 도달 스트림(GET /api/v1/fx/alerts/stream) 이벤트 data 형식을 API 문서 Schemas 에 등록한다
 * (FxAlertStreamReadyEvent, FxAlertNotification). text/event-stream 응답은 형식을 참조하지 못한다(ChatOpenApiCustomizer 와 같음).
 */
@Component
public class FxAlertOpenApiCustomizer implements OpenApiCustomizer {

    static final List<Class<?>> EVENT_TYPES = List.of(FxAlertStreamReady.class, FxAlertNotification.class);

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        for (Class<?> type : EVENT_TYPES) {
            // FxAlertNotification 은 notifications 응답이 이미 등록했으면 그대로 둔다
            ModelConverters.getInstance().readAll(new AnnotatedType(type)).forEach((name, schema) -> {
                if (components.getSchemas() == null || !components.getSchemas().containsKey(name)) {
                    components.addSchemas(name, schema);
                }
            });
        }
    }
}
