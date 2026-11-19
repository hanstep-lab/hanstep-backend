package com.hanstep.visa.service;

import static com.hanstep.visa.service.VisaRoadmapTestSupport.LLM_MODEL;
import static com.hanstep.visa.service.VisaRoadmapTestSupport.MAPPER;
import static com.hanstep.visa.service.VisaRoadmapTestSupport.insertUser;
import static com.hanstep.visa.service.VisaRoadmapTestSupport.llm;
import static com.hanstep.visa.service.VisaRoadmapTestSupport.roadmapFor;
import static com.hanstep.visa.service.VisaRoadmapTestSupport.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.visa.client.AiE74Client;
import com.hanstep.visa.client.AiRoadmapClient;
import com.hanstep.visa.client.AiRoadmapClient.AiCallException;
import com.hanstep.visa.service.VisaRoadmapTestSupport.MutableClock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * 템플릿 문장 로드맵 재사용 대신 LLM 재시도·교체(#139). AI 계산·로드맵은 가짜, 시간은 MutableClock 으로 옮긴다.
 * 쿨다운은 기본값(5분). 운영 설정(DRAFT 규칙 불허)으로 돌고 테스트마다 롤백.
 */
@SpringBootTest(properties = "hanstep.e74.include-draft-rules=false")
@Import(VisaRoadmapTemplateRetryTest.ClockConfig.class)
@Transactional
class VisaRoadmapTemplateRetryTest {

    private static final Duration COOLDOWN = Duration.ofMinutes(5);

    @TestConfiguration
    static class ClockConfig {

        @Bean
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-11-19T01:00:00Z"));
        }
    }

    @Autowired
    private VisaRoadmapService service;

    @Autowired
    private RoadmapLlmRetryCooldown cooldown;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiE74Client aiE74Client;

    @MockitoBean
    private AiRoadmapClient aiRoadmapClient;

    private long userId;
    private ObjectNode roadmap;

    @BeforeEach
    void setUp() throws IOException {
        // 시드가 바뀌기 전(DRAFT)에 만든 로컬 DB에서도 같게 돌도록 테스트 안에서 승인해 둔다(롤백)
        jdbc.update("UPDATE e74_rule_sets SET approval_status = 'APPROVED' WHERE rule_version = 'E74-2026-v1'");
        userId = insertUser(jdbc, "test-roadmap-retry");
        ObjectNode assessment = sample("e74/calculate_response.json");
        roadmap = sample("visa/roadmap_response.json");
        when(aiE74Client.calculate(any())).thenAnswer(invocation -> assessment.deepCopy());
        answerWith(roadmap);
    }

    @Test
    void LLM_문장으로_저장된_로드맵은_쿨다운이_지나도_AI를_부르지_않는다() {
        answerWith(llm(roadmap, "LLM 설명"));
        long id = service.create(userId, null, null).body().roadmapId();
        assertThat(cooldown.hasAttempt(id)).isFalse();

        clock.advance(Duration.ofHours(1));
        VisaRoadmapService.Result again = service.create(userId, null, null);

        assertThat(again.created()).isFalse();
        assertThat(again.body().reused()).isTrue();
        assertThat(again.body().roadmapId()).isEqualTo(id);
        verify(aiRoadmapClient, times(1)).create(any());
    }

    @Test
    void 템플릿_로드맵은_쿨다운_뒤_LLM_문장이_오면_같은_행을_바꾼다() {
        long id = service.create(userId, null, null).body().roadmapId();
        assertThat(cooldown.hasAttempt(id)).isTrue();
        clock.advance(COOLDOWN);
        ObjectNode replacement = llm(roadmap, "LLM 이 다듬은 첫 단계 설명");
        answerWith(replacement);

        VisaRoadmapService.Result result = service.create(userId, null, null);

        assertThat(result.created()).isTrue();
        assertThat(result.body().reused()).isFalse();
        assertThat(result.body().roadmapId()).isEqualTo(id);
        assertThat(roadmapCount()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT model_id, prompt_version, result_json FROM visa_roadmaps WHERE id = ?", id);
        assertThat(row.get("model_id")).isEqualTo(LLM_MODEL);
        assertThat(row.get("prompt_version")).isEqualTo("roadmap-v1");
        assertThat(MAPPER.readTree((String) row.get("result_json")).path("text").path("source").stringValue())
                .isEqualTo("LLM");
        // 단계는 새 응답으로 다시 넣었다(4개 그대로, 첫 단계 설명이 바뀜)
        assertThat(stepDescriptions(id)).hasSize(4).first().isEqualTo("LLM 이 다듬은 첫 단계 설명");
        assertThat(cooldown.hasAttempt(id)).isFalse();
        // 조회는 바뀐 결과를 그대로 준다
        assertThat(service.latest(userId).roadmap().path("text").path("modelId").stringValue()).isEqualTo(LLM_MODEL);
        verify(aiRoadmapClient, times(2)).create(any());
    }

    @Test
    void 다시_불러도_템플릿이면_저장된_것을_주고_쓰지_않는다() {
        long id = service.create(userId, null, null).body().roadmapId();
        String storedJson = resultJson(id);
        List<String> storedSteps = stepDescriptions(id);
        clock.advance(COOLDOWN);
        ObjectNode template = roadmap.deepCopy();
        ((ObjectNode) template.path("steps").get(0)).put("description", "다시 만든 템플릿 설명");
        answerWith(template);

        VisaRoadmapService.Result result = service.create(userId, null, null);

        assertThat(result.created()).isFalse();
        assertThat(result.body().reused()).isTrue();
        assertThat(result.body().roadmapId()).isEqualTo(id);
        assertThat(result.body().roadmap()).isEqualTo(MAPPER.readTree(storedJson));
        assertThat(resultJson(id)).isEqualTo(storedJson);
        assertThat(stepDescriptions(id)).isEqualTo(storedSteps);
        verify(aiRoadmapClient, times(2)).create(any());
    }

    @Test
    void 쿨다운_안에서는_AI를_부르지_않고_지나면_한_번_부른다() {
        long id = service.create(userId, null, null).body().roadmapId();

        clock.advance(COOLDOWN.minusSeconds(1));
        assertThat(service.create(userId, null, null).body().roadmapId()).isEqualTo(id);
        verify(aiRoadmapClient, times(1)).create(any());

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.create(userId, null, null).body().reused()).isTrue();
        verify(aiRoadmapClient, times(2)).create(any());

        // 방금 다시 불렀으므로 다음 쿨다운까지는 부르지 않는다
        service.create(userId, null, null);
        verify(aiRoadmapClient, times(2)).create(any());
    }

    @Test
    void 다시_부를_때_AI_오류면_저장된_행을_그대로_두고_502() {
        long id = service.create(userId, null, null).body().roadmapId();
        String storedJson = resultJson(id);
        clock.advance(COOLDOWN);
        doThrow(new AiCallException("AI_UNREACHABLE", null)).when(aiRoadmapClient).create(any());

        assertThatThrownBy(() -> service.create(userId, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UPSTREAM_ERROR));

        assertThat(roadmapCount()).isEqualTo(1);
        assertThat(resultJson(id)).isEqualTo(storedJson);
        assertThat(stepDescriptions(id)).hasSize(4);
        // 오류도 시도로 센다. 쿨다운 안의 다음 요청은 AI 없이 저장된 템플릿을 준다
        assertThat(service.create(userId, null, null).body().reused()).isTrue();
        verify(aiRoadmapClient, times(2)).create(any());
    }

    @Test
    void 다시_부를_때도_키와_테이블_검사를_같게_하고_어긋나면_502() {
        long id = service.create(userId, null, null).body().roadmapId();
        String storedJson = resultJson(id);

        ObjectNode otherKey = llm(roadmap, "LLM 설명");
        otherKey.put("assessmentRequestKey", "0".repeat(64));
        clock.advance(COOLDOWN);
        answerWith(otherKey);
        assertThatThrownBy(() -> service.create(userId, null, null)).isInstanceOf(BusinessException.class);

        ObjectNode badActor = llm(roadmap, "LLM 설명");
        ((ObjectNode) badActor.path("steps").get(0)).put("actor", "BANK");
        clock.advance(COOLDOWN);
        answerWith(badActor);
        assertThatThrownBy(() -> service.create(userId, null, null)).isInstanceOf(BusinessException.class);

        assertThat(resultJson(id)).isEqualTo(storedJson);
        assertThat(jdbc.queryForObject("SELECT model_id FROM visa_roadmaps WHERE id = ?", String.class, id)).isNull();
    }

    @Test
    void 채팅_도구용_생성도_같은_규칙으로_바꾼다() {
        long id = service.createForUser(userId, null, null).body().roadmapId();
        clock.advance(COOLDOWN);
        answerWith(llm(roadmap, "LLM 설명"));

        VisaRoadmapService.Result result = service.createForUser(userId, null, null);

        assertThat(result.created()).isTrue();
        assertThat(result.body().roadmapId()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT model_id FROM visa_roadmaps WHERE id = ?", String.class, id))
                .isEqualTo(LLM_MODEL);
    }

    private void answerWith(ObjectNode response) {
        doAnswer(invocation -> roadmapFor(response, invocation.getArgument(0))).when(aiRoadmapClient).create(any());
    }

    private int roadmapCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM visa_roadmaps WHERE user_id = ?", Integer.class, userId);
    }

    private String resultJson(long roadmapId) {
        return jdbc.queryForObject("SELECT result_json FROM visa_roadmaps WHERE id = ?", String.class, roadmapId);
    }

    private List<String> stepDescriptions(long roadmapId) {
        return jdbc.queryForList("SELECT description FROM visa_roadmap_steps WHERE roadmap_id = ? ORDER BY step_order",
                String.class, roadmapId);
    }
}
