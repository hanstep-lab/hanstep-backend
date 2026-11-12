package com.hanstep.visa.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-7-4 계산 POST /internal/v1/visa/e74/calculate 호출. 형식은 AI 레포 app/visa/e74/schemas.py·service.py 기준.
 * 응답은 그대로 저장(result_json)하고 돌려주므로 JsonNode 로 받는다. 테스트에서는 이 빈을 바꿔 끼운다.
 */
@Component
public class AiE74Client {

    static final String PATH = "/internal/v1/visa/e74/calculate";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final RestClient aiRestClient;

    public AiE74Client(@Qualifier("aiRestClient") RestClient aiRestClient) {
        this.aiRestClient = aiRestClient;
    }

    /**
     * 400(입력 형식 오류)은 AiValidationException 으로, 나머지 실패는 AiCallException 으로 던진다.
     * requestKey·status·score·items 가 없는 응답도 AI_BAD_RESPONSE.
     */
    public JsonNode calculate(JsonNode request) {
        String body;
        try {
            body = aiRestClient.post().uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.BadRequest e) {
            throw new AiValidationException(fields(e.getResponseBodyAsString()), e);
        } catch (RestClientResponseException e) {
            throw new AiCallException("AI_HTTP_" + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            // 연결 실패·타임아웃
            throw new AiCallException("AI_UNREACHABLE", e);
        } catch (RestClientException e) {
            throw new AiCallException("AI_BAD_RESPONSE", e);
        }
        JsonNode response = parse(body);
        if (response == null || !response.path("requestKey").isString() || !response.path("status").isString()
                || !response.path("score").isObject() || !response.path("items").isArray()) {
            throw new AiCallException("AI_BAD_RESPONSE", null);
        }
        return response;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw new AiCallException("AI_BAD_RESPONSE", e);
        }
    }

    // AI 오류 본문의 details.fields. AI 는 필드 경로와 사유만 주고 값은 되돌려 보내지 않는다
    private static List<Map<String, String>> fields(String body) {
        List<Map<String, String>> fields = new ArrayList<>();
        JsonNode root;
        try {
            root = parse(body);
        } catch (AiCallException e) {
            return fields;
        }
        if (root == null) {
            return fields;
        }
        for (JsonNode field : root.path("details").path("fields")) {
            fields.add(Map.of("field", field.path("field").asString("unknown"),
                    "reason", field.path("reason").asString("invalid")));
        }
        return fields;
    }

    /** 계산 API 호출 실패. errorCode 는 로그용(AI_HTTP_502, AI_UNREACHABLE, AI_BAD_RESPONSE). */
    public static class AiCallException extends RuntimeException {

        private final String errorCode;

        public AiCallException(String errorCode, Throwable cause) {
            super("E-7-4 calculate call failed: " + errorCode, cause);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    /** 계산 API 가 입력을 거부함(400). fields 는 AI 가 준 필드 경로·사유. */
    public static class AiValidationException extends RuntimeException {

        private final List<Map<String, String>> fields;

        public AiValidationException(List<Map<String, String>> fields, Throwable cause) {
            super("E-7-4 calculate rejected the request", cause);
            this.fields = List.copyOf(fields);
        }

        public List<Map<String, String>> getFields() {
            return fields;
        }
    }
}
