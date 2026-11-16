package com.hanstep.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 운영(prod) 프로필 API 문서에는 외부 API(/api/v1)만 나오고 내부 API 흔적이 없다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProdOpenApiDocsTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 운영_문서에는_내부_API_경로와_내부_토큰_스킴이_없다() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = MAPPER.readTree(docs);
        JsonNode paths = root.path("paths");
        JsonNode schemes = root.path("components").path("securitySchemes");

        assertThat(paths.propertyNames()).noneMatch(path -> path.startsWith("/internal"));
        assertThat(paths.has("/api/v1/auth/entry")).isTrue();
        assertThat(schemes.has(OpenApiConfig.INTERNAL_TOKEN)).isFalse();
        assertThat(schemes.has(OpenApiConfig.BEARER_AUTH)).isTrue();
        assertThat(root.path("info").path("description").stringValue()).doesNotContain("/internal", "X-Internal-Token");
    }
}
