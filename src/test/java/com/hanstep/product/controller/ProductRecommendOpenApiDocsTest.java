package com.hanstep.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** PRD-02 문서에 이름 있는 응답 예시(성공·기권·금리 null)와 maxRate 표시 규칙이 나온다. */
@SpringBootTest
@AutoConfigureMockMvc
class ProductRecommendOpenApiDocsTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 응답_예시_세_개가_이름으로_나온다() throws Exception {
        JsonNode examples = docs().path("paths").path("/api/v1/finance/products/recommend").path("get")
                .path("responses").path("200").path("content").path("application/json").path("examples");

        // 이름이 없으면 springdoc 이 예시를 하나로 덮어쓴다(#101)
        assertThat(examples.propertyNames()).containsExactlyInAnyOrder("성공", "기권", "금리 null");
        assertThat(examples.path("기권").path("value").path("abstainReason").stringValue())
                .isEqualTo("REQUEST_EXCEEDS_SURPLUS");
        assertThat(examples.path("금리 null").path("value").path("products").path(0).path("maxRate").isNull())
                .isTrue();
    }

    @Test
    void maxRate_설명에_우대_조건_충족_시_문구가_있다() throws Exception {
        JsonNode schemas = docs().path("components").path("schemas");

        assertThat(schemas.path("ProductRecommendItem").path("properties").path("maxRate").toString())
                .contains("우대 조건 충족 시 최고 금리(원문 값)", "'우대 조건 충족 시' 문구와 함께 표시");
        assertThat(schemas.path("ProductRecommendMaxRate").path("description").stringValue())
                .contains("우대 조건 충족 시 최고 금리(원문 값)");
    }

    private JsonNode docs() throws Exception {
        return MAPPER.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
