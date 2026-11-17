package com.hanstep.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영(prod) API 문서에서 내부 API 흔적을 뺀다. 로컬 문서는 그대로.
 *
 * <p>경로는 application-prod.yaml 의 springdoc.paths-to-match(/api/v1/**)로 이미 빠지고,
 * 여기서는 남는 internalToken 보안 스킴과 설명의 내부 API 안내 문장을 지운다.
 */
@Component
@Profile("prod")
public class ProdOpenApiCustomizer implements OpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() != null && openApi.getComponents().getSecuritySchemes() != null) {
            openApi.getComponents().getSecuritySchemes().remove(OpenApiConfig.INTERNAL_TOKEN);
        }
        if (openApi.getInfo() != null && openApi.getInfo().getDescription() != null) {
            openApi.getInfo().setDescription(
                    openApi.getInfo().getDescription().replace(OpenApiConfig.INTERNAL_API_NOTE, ""));
        }
    }
}
