package com.hanstep.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI(/swagger-ui.html) 설정.
 * 오른쪽 위 Authorize 버튼에 /auth/entry 로 받은 accessToken 을 넣으면 이후 요청에 Bearer 헤더가 붙음.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
    /** AI → Spring 내부 API(/internal/v1/**). 컨트롤러에 @SecurityRequirement(name = INTERNAL_TOKEN) 로 붙인다. */
    public static final String INTERNAL_TOKEN = "internalToken";

    @Bean
    public OpenAPI hanstepOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("신한걸음 API")
                        .version("v1")
                        .description("""
                                1. POST /api/v1/auth/entry 에 entryToken(데모: demo-e9-nguyen)을 보내 accessToken 을 받는다.
                                2. Authorize 버튼에 accessToken 을 넣는다(Bearer 는 자동으로 붙음).
                                오류 응답은 모두 { code, message, details, requestId } 형식.
                                내부 API(/internal/v1/**)는 JWT 대신 X-Internal-Token(SPRING_INTERNAL_API_KEY)을 쓴다.
                                """))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes(INTERNAL_TOKEN, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Token")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
