package com.hanstep.visa.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.user.repository.UserRepository;
import com.hanstep.visa.client.AiRoadmapClient;
import com.hanstep.visa.client.AiRoadmapClient.AiCallException;
import com.hanstep.visa.dto.E74AssessmentResponse;
import com.hanstep.visa.dto.LegalDeclaration;
import com.hanstep.visa.dto.VisaRoadmapDetailResponse;
import com.hanstep.visa.dto.VisaRoadmapResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 비자 로드맵. 단계·점수·날짜는 AI 로드맵 API(규칙 엔진, 문장만 LLM)가 만들고, 여기서는 같은 프로필로 E-7-4 평가를
 * 확보한 뒤 로드맵을 받아 그대로 쌓는다. 평가와 체류 만료일이 같으면 저장된 로드맵을 돌려준다.
 * 저장된 로드맵이 LLM 문장(model_id 있음)이면 AI 를 부르지 않는다. 템플릿 문장(model_id NULL)이면 쿨다운마다 한 번 AI 를
 * 다시 불러 LLM 문장이 오면 같은 행(roadmapId 그대로)을 바꾸고, 또 템플릿이면 저장된 것을 그대로 준다(#139).
 * AI 응답을 기다리는 동안 DB 커넥션을 잡지 않도록 AI 호출은 트랜잭션 밖에서 하고 저장만 짧게 묶는다.
 */
@Service
public class VisaRoadmapService {

    private static final Logger log = LoggerFactory.getLogger(VisaRoadmapService.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private static final String INSERT_ROADMAP = """
            INSERT INTO visa_roadmaps (user_id, assessment_id, stay_expires_on, goal_visa_code, warnings_json, model_id,
                prompt_version, result_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_STEP = """
            INSERT INTO visa_roadmap_steps (roadmap_id, step_order, title, description, related_rule_code,
                estimated_additional_score, target_date, actor, required_evidence_json, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECOMMENDED')""";
    // 아직 템플릿 문장일 때만 바꾼다. 같은 행을 동시에 바꾸는 요청은 행 잠금을 기다렸다가 0행이 된다
    private static final String REPLACE_ROADMAP = """
            UPDATE visa_roadmaps SET goal_visa_code = ?, warnings_json = ?, model_id = ?, prompt_version = ?,
                result_json = ?
            WHERE id = ? AND model_id IS NULL""";

    // visa_roadmap_steps.actor 의 ENUM(01_hanstep_schema.sql). AI 의 RuleActor 와 같은 값이다
    private static final Set<String> ACTORS = Set.of("USER", "EMPLOYER", "GOVERNMENT");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AiRoadmapClient aiRoadmapClient;
    private final E74ProfileSnapshotReader snapshotReader;
    private final E74AssessmentService e74AssessmentService;
    private final UserRepository userRepository;
    private final RoadmapLlmRetryCooldown retryCooldown;

    public VisaRoadmapService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                              AiRoadmapClient aiRoadmapClient, E74ProfileSnapshotReader snapshotReader,
                              E74AssessmentService e74AssessmentService, UserRepository userRepository,
                              RoadmapLlmRetryCooldown retryCooldown) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.aiRoadmapClient = aiRoadmapClient;
        this.snapshotReader = snapshotReader;
        this.e74AssessmentService = e74AssessmentService;
        this.userRepository = userRepository;
        this.retryCooldown = retryCooldown;
    }

    /**
     * created=true 면 새로 저장했거나 템플릿 문장 로드맵을 LLM 문장으로 바꾼 것(201). false 면 같은 평가·체류 만료일로
     * 저장된 로드맵을 그대로 돌려준 것(200, 쓰기 없음).
     */
    public record Result(VisaRoadmapResponse body, boolean created) {
    }

    /** 저장된 행. template 은 model_id 가 NULL(템플릿 문장)인지. */
    private record Stored(long id, boolean template, JsonNode roadmap) {

        Result reused() {
            return new Result(new VisaRoadmapResponse(id, true, roadmap), false);
        }
    }

    /** 채팅 도구용 내부 API. 없는 사용자면 404. */
    public Result createForUser(Long userId, LocalDate asOfDate, LegalDeclaration legalDeclaration) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return create(userId, asOfDate, legalDeclaration);
    }

    /** asOfDate 가 없으면 오늘(KST). 평가에서 나는 오류(409·422·502)는 그대로 올린다. */
    public Result create(Long userId, LocalDate asOfDate, LegalDeclaration legalDeclaration) {
        LocalDate asOf = asOfDate != null ? asOfDate : LocalDate.now(KstDates.KST);
        E74ProfileSnapshotReader.Snapshot snapshot = snapshotReader.readWithStay(userId);
        LocalDate stayExpiresOn = snapshot.stayExpiresOn();
        if (stayExpiresOn == null) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_DATA, "stay expiry date is missing",
                    Map.of("reason", "NO_STAY_EXPIRY"));
        }
        // AI 로드맵 API 도 400 으로 거절하는 경우라 평가 전에 막는다
        if (stayExpiresOn.isBefore(asOf)) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_DATA, "stay has expired",
                    Map.of("reason", "STAY_EXPIRED"));
        }

        // 평가와 로드맵에 같은 profile 을 보내야 로드맵의 assessmentRequestKey 가 평가의 requestKey 와 같다
        ObjectNode profile = E74AssessmentService.withLegalDeclaration(snapshot.profile(), legalDeclaration);
        E74AssessmentResponse assessment = e74AssessmentService.assessProfile(userId, profile, asOf).body();
        long assessmentId = assessment.assessmentId();
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("asOfDate", asOf.toString());
        request.put("stayExpiresOn", stayExpiresOn.toString());
        request.set("profile", profile);
        e74AssessmentService.putLanguage(request, userId);

        Optional<Stored> existing = find(userId, assessmentId, stayExpiresOn);
        if (existing.isPresent()) {
            Stored stored = existing.get();
            if (!stored.template() || !retryCooldown.tryAcquire(stored.id())) {
                return stored.reused();
            }
            return retryLlmText(userId, assessment, stayExpiresOn, request, stored);
        }
        return save(userId, assessmentId, stayExpiresOn, requestRoadmap(userId, assessment, stayExpiresOn, request));
    }

    /**
     * 템플릿 문장 로드맵에 AI 를 다시 불러 LLM 문장이 오면 같은 행을 바꾼다. 또 템플릿이면 쓰지 않고 저장된 것을 준다.
     * AI 오류·키 불일치·저장할 수 없는 응답이면 저장된 행을 건드리지 않고 새로 만들 때와 같은 오류(502)를 올린다.
     */
    private Result retryLlmText(Long userId, E74AssessmentResponse assessment, LocalDate stayExpiresOn,
                                ObjectNode request, Stored stored) {
        JsonNode roadmap = requestRoadmap(userId, assessment, stayExpiresOn, request);
        if (isTemplate(roadmap)) {
            return stored.reused();
        }
        if (!Boolean.TRUE.equals(transactionTemplate.execute(status -> replace(stored.id(), roadmap)))) {
            // 다른 요청이 먼저 바꿨다. 지금 저장된 것을 준다(그사이 지워졌으면 새로 저장)
            return find(userId, assessment.assessmentId(), stayExpiresOn).map(Stored::reused)
                    .orElseGet(() -> save(userId, assessment.assessmentId(), stayExpiresOn, roadmap));
        }
        retryCooldown.clear(stored.id());
        log.info("visa roadmap text replaced with LLM: userId={} roadmapId={}", userId, stored.id());
        return new Result(new VisaRoadmapResponse(stored.id(), false, roadmap), true);
    }

    /** AI 로드맵을 받아 이 평가에 묶을 수 있고 테이블에 들어가는지 확인한다. 아니면 502. */
    private JsonNode requestRoadmap(Long userId, E74AssessmentResponse assessment, LocalDate stayExpiresOn,
                                    ObjectNode request) {
        JsonNode roadmap;
        try {
            roadmap = aiRoadmapClient.create(request);
        } catch (AiCallException e) {
            log.warn("visa roadmap call failed: userId={} errorCode={}", userId, e.getErrorCode(), e);
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }

        // 다른 입력으로 계산한 로드맵이면 이 평가(assessment_id)에 묶을 수 없다
        String assessmentKey = assessment.result().path("requestKey").stringValue();
        String roadmapKey = roadmap.path("assessmentRequestKey").stringValue();
        if (!assessmentKey.equals(roadmapKey)) {
            log.warn("visa roadmap key mismatch: userId={} assessmentKey={} roadmapKey={}", userId,
                    prefix(assessmentKey), prefix(roadmapKey));
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
        // ENUM·NOT NULL·길이·UNIQUE 에 맞지 않으면 저장할 수 없다. AI 응답이 약속과 다른 것이라 502
        String mismatch = schemaMismatch(roadmap, stayExpiresOn);
        if (mismatch != null) {
            log.warn("visa roadmap does not fit the tables: userId={} field={}", userId, mismatch);
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
        return roadmap;
    }

    private Result save(Long userId, long assessmentId, LocalDate stayExpiresOn, JsonNode roadmap) {
        try {
            Long roadmapId = transactionTemplate.execute(status -> insert(userId, assessmentId, stayExpiresOn, roadmap));
            if (isTemplate(roadmap)) {
                // 방금 AI 를 불렀으므로 LLM 재시도는 쿨다운 뒤
                retryCooldown.record(roadmapId);
            }
            return new Result(new VisaRoadmapResponse(roadmapId, false, roadmap), true);
        } catch (DuplicateKeyException e) {
            // 같은 평가·체류 만료일이 동시에 들어오면 먼저 저장된 행을 돌려준다
            return find(userId, assessmentId, stayExpiresOn).map(Stored::reused).orElseThrow(() -> e);
        }
    }

    /** 최신 1건. 없으면 404. */
    public VisaRoadmapDetailResponse latest(Long userId) {
        return jdbcTemplate.query("SELECT id, created_at, result_json FROM visa_roadmaps WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT 1", VisaRoadmapService::detail, userId)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 본인 행만. 남의 id 도 404(있는지 알려 주지 않음). */
    public VisaRoadmapDetailResponse get(Long userId, Long roadmapId) {
        return jdbcTemplate.query("SELECT id, created_at, result_json FROM visa_roadmaps WHERE id = ? AND user_id = ?",
                        VisaRoadmapService::detail, roadmapId, userId)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Optional<Stored> find(Long userId, long assessmentId, LocalDate stayExpiresOn) {
        return jdbcTemplate.query("""
                                SELECT id, model_id, result_json FROM visa_roadmaps
                                WHERE user_id = ? AND assessment_id = ? AND stay_expires_on = ?""",
                        (rs, i) -> new Stored(rs.getLong("id"), rs.getString("model_id") == null,
                                json(rs.getString("result_json"))),
                        userId, assessmentId, stayExpiresOn)
                .stream().findFirst();
    }

    // 템플릿 문장이면 AI 가 text.modelId 를 null 로 준다(저장하면 model_id NULL)
    private static boolean isTemplate(JsonNode roadmap) {
        return text(roadmap.path("text").path("modelId")) == null;
    }

    /** 저장된 템플릿 행을 새 응답으로 바꾸고 단계를 다시 넣는다. 이미 LLM 문장으로 바뀌었으면 false(아무것도 안 씀). */
    private boolean replace(long roadmapId, JsonNode roadmap) {
        JsonNode text = roadmap.path("text");
        int updated = jdbcTemplate.update(REPLACE_ROADMAP, roadmap.path("goalVisaCode").stringValue(),
                jsonText(roadmap.path("warnings")), text(text.path("modelId")), text(text.path("promptVersion")),
                MAPPER.writeValueAsString(roadmap), roadmapId);
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update("DELETE FROM visa_roadmap_steps WHERE roadmap_id = ?", roadmapId);
        insertSteps(roadmapId, roadmap);
        return true;
    }

    private Long insert(Long userId, long assessmentId, LocalDate stayExpiresOn, JsonNode roadmap) {
        JsonNode text = roadmap.path("text");
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_ROADMAP, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setLong(2, assessmentId);
            ps.setObject(3, stayExpiresOn);
            ps.setString(4, roadmap.path("goalVisaCode").stringValue());
            ps.setString(5, jsonText(roadmap.path("warnings")));
            // 템플릿 문장이면 AI 가 둘 다 null 로 준다
            ps.setString(6, text(text.path("modelId")));
            ps.setString(7, text(text.path("promptVersion")));
            ps.setString(8, MAPPER.writeValueAsString(roadmap));
            return ps;
        }, keyHolder);
        long roadmapId = keyHolder.getKey().longValue();
        insertSteps(roadmapId, roadmap);
        return roadmapId;
    }

    private void insertSteps(long roadmapId, JsonNode roadmap) {
        List<Object[]> steps = new ArrayList<>();
        for (JsonNode step : roadmap.path("steps")) {
            String targetDate = text(step.path("targetDate"));
            steps.add(new Object[] {roadmapId, step.path("order").intValue(), step.path("title").stringValue(),
                    text(step.path("description")), text(step.path("ruleCode")),
                    integer(step.path("estimatedAdditionalScore")),
                    targetDate != null ? LocalDate.parse(targetDate) : null, step.path("actor").stringValue(),
                    jsonText(step.path("requiredEvidence"))});
        }
        if (!steps.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_STEP, steps);
        }
    }

    // 저장할 수 없는 로드맵이면 걸린 필드 경로(로그용), 맞으면 null. 길이는 컬럼 길이(문자 수)
    private static String schemaMismatch(JsonNode roadmap, LocalDate stayExpiresOn) {
        // 재사용 키(stay_expires_on)와 저장할 원문이 어긋나지 않게 보낸 만료일과 같은지 본다
        if (!stayExpiresOn.toString().equals(text(roadmap.path("stay").path("stayExpiresOn")))) {
            return "stay.stayExpiresOn";
        }
        if (!fits(roadmap.path("goalVisaCode"), 20, false)) {
            return "goalVisaCode";
        }
        if (!fits(roadmap.path("text").path("modelId"), 60, true)) {
            return "text.modelId";
        }
        if (!fits(roadmap.path("text").path("promptVersion"), 40, true)) {
            return "text.promptVersion";
        }
        JsonNode steps = roadmap.path("steps");
        Set<Integer> orders = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String path = "steps." + i;
            JsonNode order = step.path("order");
            // SMALLINT UNSIGNED, UNIQUE(roadmap_id, step_order)
            if (!order.isInt() || order.intValue() < 0 || order.intValue() > 65_535 || !orders.add(order.intValue())) {
                return path + ".order";
            }
            if (!fits(step.path("title"), 200, false)) {
                return path + ".title";
            }
            if (!fits(step.path("description"), 1000, true)) {
                return path + ".description";
            }
            if (!fits(step.path("ruleCode"), 60, true)) {
                return path + ".ruleCode";
            }
            if (!smallint(step.path("estimatedAdditionalScore"))) {
                return path + ".estimatedAdditionalScore";
            }
            if (!date(step.path("targetDate"))) {
                return path + ".targetDate";
            }
            JsonNode actor = step.path("actor");
            if (!actor.isString() || !ACTORS.contains(actor.stringValue())) {
                return path + ".actor";
            }
        }
        return null;
    }

    // VARCHAR(maxChars) 에 들어가는 문자열. nullable 이면 null 도 된다
    private static boolean fits(JsonNode value, int maxChars, boolean nullable) {
        if (value.isNull() || value.isMissingNode()) {
            return nullable;
        }
        return value.isString() && value.stringValue().codePointCount(0, value.stringValue().length()) <= maxChars;
    }

    private static boolean smallint(JsonNode value) {
        return value.isNull() || value.isMissingNode()
                || (value.isInt() && value.intValue() >= Short.MIN_VALUE && value.intValue() <= Short.MAX_VALUE);
    }

    private static boolean date(JsonNode value) {
        if (value.isNull() || value.isMissingNode()) {
            return true;
        }
        if (!value.isString()) {
            return false;
        }
        try {
            LocalDate.parse(value.stringValue());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String prefix(String key) {
        return key == null ? null : key.substring(0, Math.min(12, key.length()));
    }

    private static VisaRoadmapDetailResponse detail(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
        OffsetDateTime kst = createdAt == null ? null
                : createdAt.toInstant(ZoneOffset.UTC).atOffset(KST).truncatedTo(ChronoUnit.SECONDS);
        return new VisaRoadmapDetailResponse(rs.getLong("id"), kst, json(rs.getString("result_json")));
    }

    private static Integer integer(JsonNode value) {
        return value.isIntegralNumber() ? value.intValue() : null;
    }

    private static String text(JsonNode value) {
        return value.isString() ? value.stringValue() : null;
    }

    private static String jsonText(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? null : MAPPER.writeValueAsString(value);
    }

    private static JsonNode json(String value) {
        return value == null ? JsonNodeFactory.instance.nullNode() : MAPPER.readTree(value);
    }
}
