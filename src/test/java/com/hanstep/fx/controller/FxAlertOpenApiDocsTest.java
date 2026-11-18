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

/** FX-002 문서에 응답 코드마다 이름 있는 예시가 나온다(이름이 없으면 springdoc 이 하나로 덮어씀, #101). */
@SpringBootTest
@AutoConfigureMockMvc
class FxAlertOpenApiDocsTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 만들기_응답_코드마다_예시가_있다() throws Exception {
        JsonNode responses = docs().path("paths").path("/api/v1/fx/alerts").path("post").path("responses");

        assertThat(responses.propertyNames()).containsExactlyInAnyOrder("201", "400", "401", "404", "409");
        assertThat(examples(responses, "201").path("성공").path("value").path("targetRate").isNumber()).isTrue();
        assertThat(examples(responses, "400").propertyNames())
                .containsExactlyInAnyOrder("형식 오류", "송금 환율 없음", "본문 형식 오류");
        assertThat(examples(responses, "400").path("송금 환율 없음").path("value").path("details").path("reason")
                .stringValue()).isEqualTo("SEND_RATE_NOT_AVAILABLE");
        assertThat(examples(responses, "404").path("환율 없는 통화").path("value").path("code").stringValue())
                .isEqualTo("FX_RATE_NOT_FOUND");
        assertThat(examples(responses, "409").path("이미 있음").path("value").path("code").stringValue())
                .isEqualTo("DUPLICATE_REQUEST");
        assertThat(examples(responses, "401").isEmpty()).isFalse();
    }

    @Test
    void 목록과_지우기_응답_코드마다_예시가_있다() throws Exception {
        JsonNode paths = docs().path("paths");
        JsonNode list = paths.path("/api/v1/fx/alerts").path("get").path("responses");
        JsonNode delete = paths.path("/api/v1/fx/alerts/{alertId}").path("delete").path("responses");

        assertThat(examples(list, "200").propertyNames()).containsExactlyInAnyOrder("성공", "알림 없음");
        assertThat(examples(list, "200").path("성공").path("value").toString()).contains("TRIGGERED", "triggeredRate");
        assertThat(examples(list, "401").isEmpty()).isFalse();
        assertThat(delete.propertyNames()).containsExactlyInAnyOrder("204", "400", "401", "404");
        assertThat(delete.path("204").path("content").isMissingNode()).isTrue();
        assertThat(examples(delete, "404").path("없음").path("value").path("code").stringValue())
                .isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void 보충_조회와_알렸음_응답_코드마다_예시가_있다() throws Exception {
        JsonNode paths = docs().path("paths");
        JsonNode notifications = paths.path("/api/v1/fx/alerts/notifications").path("get").path("responses");
        JsonNode notified = paths.path("/api/v1/fx/alerts/{alertId}/notified").path("post").path("responses");

        assertThat(notifications.propertyNames()).containsExactlyInAnyOrder("200", "401");
        assertThat(examples(notifications, "200").propertyNames()).containsExactlyInAnyOrder("성공", "없음");
        assertThat(examples(notifications, "200").path("성공").path("value").path("notifications").get(0)
                .propertyNames()).containsExactly("alertId", "currency", "rateType", "comparator", "targetRate",
                "unitAmount", "triggeredRate", "triggeredAt", "rateDate", "source");
        assertThat(notified.propertyNames()).containsExactlyInAnyOrder("204", "400", "401", "404", "409");
        assertThat(examples(notified, "409").path("도달 전").path("value").path("code").stringValue())
                .isEqualTo("INVALID_STATE");
    }

    @Test
    void 스트림_이벤트_형식과_예시가_있다() throws Exception {
        JsonNode docs = docs();
        JsonNode schemas = docs.path("components").path("schemas");
        JsonNode stream = docs.path("paths").path("/api/v1/fx/alerts/stream").path("get").path("responses");

        assertThat(schemas.propertyNames()).contains("FxAlertStreamReadyEvent", "FxAlertNotification");
        assertThat(schemas.path("FxAlertNotification").path("properties").path("source").path("enum").toString())
                .contains("KOREAEXIM", "MARKET_REF");
        assertThat(schemas.path("FxAlertResponse").path("properties").has("notifiedAt")).isTrue();
        assertThat(stream.path("200").path("content").path("text/event-stream").path("examples").toString())
                .contains("event:ready", "event:fx-alert-triggered", "id:12", ": ping");
        assertThat(examples(stream, "401").isEmpty()).isFalse();
    }

    @Test
    void 실시간_문구가_없다() throws Exception {
        JsonNode docs = docs();
        JsonNode schemas = docs.path("components").path("schemas");
        StringBuilder fxDocs = new StringBuilder();
        for (String path : new String[] {"/api/v1/fx/alerts", "/api/v1/fx/alerts/{alertId}",
                "/api/v1/fx/alerts/notifications", "/api/v1/fx/alerts/{alertId}/notified", "/api/v1/fx/alerts/stream"}) {
            fxDocs.append(docs.path("paths").path(path));
        }
        for (String schema : new String[] {"FxAlertCreateRequest", "FxAlertResponse", "FxAlertListResponse",
                "FxAlertNotification", "FxAlertNotificationListResponse", "FxAlertStreamReadyEvent"}) {
            assertThat(schemas.path(schema).isMissingNode()).as(schema).isFalse();
            fxDocs.append(schemas.path(schema));
        }

        assertThat(fxDocs.toString()).doesNotContain("실시간", "real-time", "realtime");
    }

    private static JsonNode examples(JsonNode responses, String code) {
        return responses.path(code).path("content").path("application/json").path("examples");
    }

    private JsonNode docs() throws Exception {
        return MAPPER.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
