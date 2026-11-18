package com.hanstep.fx.controller;

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

/** 환율 화면 API 문서: 이름 있는 예시가 나오고, "실시간"·종가(close) 표현을 쓰지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
class FxRateOpenApiDocsTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 현재값과_추이에_실제_출력_예시가_있다() throws Exception {
        JsonNode paths = docs().path("paths");
        JsonNode rate = examples(paths.path("/api/v1/fx/rates").path("get"));
        JsonNode history = examples(paths.path("/api/v1/fx/rates/{currency}/history").path("get"));

        assertThat(rate.path("Reyes PHP").path("value").path("notices").path(0).stringValue())
                .isEqualTo("REFERENCE_RATE_ONLY");
        assertThat(rate.path("Reyes PHP").path("value").path("periodAverages").size()).isEqualTo(4);
        assertThat(history.path("Reyes PHP 1M").path("value").path("points").isArray()).isTrue();
    }

    @Test
    void 실시간_종가_표현을_쓰지_않는다() throws Exception {
        JsonNode docs = docs();
        JsonNode schemas = docs.path("components").path("schemas");
        String text = docs.path("paths").path("/api/v1/fx/rates").toString()
                + docs.path("paths").path("/api/v1/fx/rates/{currency}/history").toString()
                + schemas.path("FxRateResponse") + schemas.path("FxPeriodAverage") + schemas.path("FxRateHistoryResponse")
                + schemas.path("FxRatePoint");

        assertThat(schemas.path("FxRateResponse").isMissingNode()).isFalse();
        assertThat(text).doesNotContain("실시간", "종가").doesNotContainIgnoringCase("close");
    }

    private static JsonNode examples(JsonNode operation) {
        return operation.path("responses").path("200").path("content").path("application/json").path("examples");
    }

    private JsonNode docs() throws Exception {
        return MAPPER.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
