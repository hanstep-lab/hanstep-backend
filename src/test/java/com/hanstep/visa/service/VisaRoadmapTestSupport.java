package com.hanstep.visa.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 로드맵 템플릿 재시도(#139) 테스트 공용. 샘플은 VisaRoadmapApiTest 와 같다(같은 입력에서 나와 assessmentRequestKey 가
 * 평가의 requestKey 와 같음). 샘플 로드맵의 text 는 템플릿 문장(modelId null)이다.
 */
final class VisaRoadmapTestSupport {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String LLM_MODEL = "claude-haiku-5-5";

    private VisaRoadmapTestSupport() {
    }

    static ObjectNode sample(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return (ObjectNode) MAPPER.readTree(in);
        }
    }

    /** AI 로드맵처럼 보낸 체류 만료일을 응답 stay 와 신청 단계 목표일에 그대로 담는다. */
    static JsonNode roadmapFor(ObjectNode roadmap, JsonNode request) {
        ObjectNode response = roadmap.deepCopy();
        ((ObjectNode) response.path("stay")).put("stayExpiresOn", request.path("stayExpiresOn").stringValue());
        ArrayNode steps = (ArrayNode) response.path("steps");
        ((ObjectNode) steps.get(steps.size() - 1)).put("targetDate", request.path("stayExpiresOn").stringValue());
        return response;
    }

    /** LLM 문장 응답: text 에 모델을 넣고 첫 단계 설명을 바꾼다(교체됐는지 단계 행으로 보이게). */
    static ObjectNode llm(ObjectNode roadmap, String description) {
        ObjectNode copy = roadmap.deepCopy();
        ObjectNode text = (ObjectNode) copy.path("text");
        text.put("source", "LLM");
        text.put("modelId", LLM_MODEL);
        text.put("promptVersion", "roadmap-v1");
        ((ObjectNode) copy.path("steps").get(0)).put("description", description);
        return copy;
    }

    /** AI 를 가짜로 바꿔 끼우므로 프로필은 체류 정보만 넣는다(만료일은 로드맵 샘플과 같은 날). */
    static long insertUser(JdbcTemplate jdbc, String key) {
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES (?, 'TEST ROADMAP')", key);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = ?", Long.class, key);
        jdbc.update("""
                INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, birth_date, current_stay_started_on,
                                           evidence_status)
                SELECT ?, id, '2027-10-04', '1994-03-12', '2021-04-01', 'VERIFIED' FROM visa_types WHERE code = 'E-9'""",
                id);
        return id;
    }

    /** 테스트가 시간을 옮기는 시계. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
