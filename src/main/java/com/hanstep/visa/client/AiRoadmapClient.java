package com.hanstep.visa.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 비자 로드맵 POST /internal/v1/visa/roadmap 호출. 형식은 AI 레포 app/visa/roadmap/schemas.py 기준.
 * 응답은 그대로 저장(result_json)하고 돌려주므로 JsonNode 로 받는다. 테스트에서는 이 빈을 바꿔 끼운다.
 */
@Component
public class AiRoadmapClient {

    static final String PATH = "/internal/v1/visa/roadmap";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final RestClient aiRestClient;

    public AiRoadmapClient(@Qualifier("aiRestClient") RestClient aiRestClient) {
        this.aiRestClient = aiRestClient;
    }

    /**
     * 실패는 모두 AiCallException 으로 던진다. 보내는 profile 은 바로 앞 E-7-4 계산이 받아 준 값이라
     * 400 도 AI 쪽 문제로 본다. assessmentRequestKey·stay·steps 가 없는 응답도 AI_BAD_RESPONSE.
     */
    public JsonNode create(JsonNode request) {
        String body;
        try {
            body = aiRestClient.post().uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new AiCallException("AI_HTTP_" + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            // 연결 실패·타임아웃(LLM 문장이 켜져 있으면 최대 약 15초)
            throw new AiCallException("AI_UNREACHABLE", e);
        } catch (RestClientException e) {
            throw new AiCallException("AI_BAD_RESPONSE", e);
        }
        JsonNode response = parse(body);
        if (response == null || !response.path("assessmentRequestKey").isString() || !response.path("stay").isObject()
                || !response.path("steps").isArray()) {
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

    /** 로드맵 API 호출 실패. errorCode 는 로그용(AI_HTTP_502, AI_UNREACHABLE, AI_BAD_RESPONSE). */
    public static class AiCallException extends RuntimeException {

        private final String errorCode;

        public AiCallException(String errorCode, Throwable cause) {
            super("visa roadmap call failed: " + errorCode, cause);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
