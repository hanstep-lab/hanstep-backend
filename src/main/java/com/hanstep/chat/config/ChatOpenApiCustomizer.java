package com.hanstep.chat.config;

import com.hanstep.chat.dto.ChatStreamEvents;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * CHT-04 스트림 이벤트 data 형식을 API 문서 Schemas 에 등록한다(ChatStartedEvent 등).
 * 스트림 응답(text/event-stream)은 이 형식을 참조하지 못해 springdoc 이 스스로 등록하지 않는다. 로컬·운영 문서 모두.
 */
@Component
public class ChatOpenApiCustomizer implements OpenApiCustomizer {

    static final List<Class<?>> EVENT_TYPES = List.of(
            ChatStreamEvents.Started.class,
            ChatStreamEvents.ToolCall.class,
            ChatStreamEvents.Delta.class,
            ChatStreamEvents.Sources.class,
            ChatStreamEvents.Done.class,
            ChatStreamEvents.ErrorEvent.class);

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        for (Class<?> type : EVENT_TYPES) {
            // 참조하는 형식(ChatToolResultRef·ChatSource)까지 함께 등록된다
            ModelConverters.getInstance().readAll(new AnnotatedType(type))
                    .forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
        }
    }
}
