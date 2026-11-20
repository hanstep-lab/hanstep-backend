package com.hanstep.chat.config;

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

/** 챗봇 API 문서에 스트림 이벤트 형식과 응답 예시가 나온다(FE 확인 요청 11/17). */
@SpringBootTest
@AutoConfigureMockMvc
class ChatOpenApiDocsTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 스트림_이벤트_형식이_Schemas에_있다() throws Exception {
        JsonNode schemas = docs().path("components").path("schemas");

        assertThat(schemas.propertyNames()).contains("ChatStartedEvent", "ChatToolCallEvent", "ChatDeltaEvent",
                "ChatSourcesEvent", "ChatDoneEvent", "ChatErrorEvent", "ChatToolResultRef");
        assertThat(schemas.path("ChatToolCallEvent").path("properties").path("status").path("enum").toString())
                .contains("SUCCEEDED", "FAILED", "TIMEOUT", "REJECTED");
        assertThat(schemas.path("ChatErrorEvent").path("properties").path("code").path("enum").toString())
                .contains("AI_UPSTREAM_ERROR", "INTERNAL_ERROR");
    }

    @Test
    void 대화방_생성_메시지_목록_스트림에_예시가_있다() throws Exception {
        JsonNode paths = docs().path("paths");

        JsonNode create = paths.path("/api/v1/chat/sessions").path("post").path("responses").path("201")
                .path("content").path("application/json").path("examples");
        JsonNode messages = paths.path("/api/v1/chat/sessions/{sessionId}/messages").path("get").path("responses")
                .path("200").path("content").path("application/json").path("examples");
        JsonNode stream = paths.path("/api/v1/chat/sessions/{sessionId}/messages/stream").path("post")
                .path("responses").path("200").path("content").path("text/event-stream").path("examples");

        assertThat(create.isEmpty()).isFalse();
        assertThat(messages.isEmpty()).isFalse();
        assertThat(stream.toString()).contains("chat.tool_call", "chat.done", "chat.error");
    }

    @Test
    void blocks_예시와_설명이_실제_카드_형식이다() throws Exception {
        JsonNode docs = docs();
        JsonNode paths = docs.path("paths");
        String messages = paths.path("/api/v1/chat/sessions/{sessionId}/messages").path("get").path("responses")
                .path("200").path("content").path("application/json").path("examples").toString();
        String stream = paths.path("/api/v1/chat/sessions/{sessionId}/messages/stream").path("post")
                .path("responses").path("200").path("content").path("text/event-stream").path("examples").toString();
        JsonNode schemas = docs.path("components").path("schemas");

        assertThat(messages).contains("REMITTANCE_CONFIRM").doesNotContain("BALANCE_CARD");
        assertThat(stream).contains("REMITTANCE_CONFIRM", "FX_SUMMARY_CARD", "E74_SCORE_CARD", "PRODUCT_RECOMMEND_CARD")
                .doesNotContain("BALANCE_CARD");
        // blocks(JsonNode)는 JsonNode 스키마 참조로 나온다. ChatDoneEvent 는 설명이 참조 대상에만 붙어 그쪽에서 읽는다
        for (String schema : new String[] {"ChatDoneEvent", "ChatMessage"}) {
            JsonNode blocks = schemas.path(schema).path("properties").path("blocks");
            String description = blocks.has("description") ? blocks.path("description").asString()
                    : schemas.path(blocks.path("$ref").asString().replace("#/components/schemas/", ""))
                            .path("description").asString();
            assertThat(description).contains("REMITTANCE_CONFIRM", "E74_SCORE_CARD", "MISSING_INPUTS",
                    "FX_SUMMARY_CARD", "PRODUCT_RECOMMEND_CARD");
        }
    }

    private JsonNode docs() throws Exception {
        return MAPPER.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
