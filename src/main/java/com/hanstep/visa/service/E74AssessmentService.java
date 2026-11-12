package com.hanstep.visa.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.user.repository.UserRepository;
import com.hanstep.visa.client.AiE74Client;
import com.hanstep.visa.client.AiE74Client.AiCallException;
import com.hanstep.visa.client.AiE74Client.AiValidationException;
import com.hanstep.visa.config.E74Properties;
import com.hanstep.visa.dto.E74AssessmentDetailResponse;
import com.hanstep.visa.dto.E74AssessmentResponse;
import com.hanstep.visa.dto.LegalDeclaration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * E-7-4 평가. 점수는 AI 규칙 엔진(LLM 아님)이 계산하고, 여기서는 입력 스냅샷을 보내고 결과를 그대로 쌓는다(append-only, FR-031).
 * AI requestKey(프로필·기준일·규칙 버전·시나리오의 해시)가 같으면 새로 저장하지 않고 저장된 결과를 돌려준다.
 * 승인된 규칙 버전(e74_rule_sets.approval_status = APPROVED)의 결과만 저장·반환한다(시연 설정이면 DRAFT 도).
 * AI 응답을 기다리는 동안 DB 커넥션을 잡지 않도록 AI 호출은 트랜잭션 밖에서 하고 저장만 짧게 묶는다.
 */
@Service
public class E74AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(E74AssessmentService.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private static final String INSERT_ASSESSMENT = """
            INSERT INTO e74_assessments (user_id, rule_set_id, request_key, as_of_date, is_scenario, scenario_json,
                input_snapshot, base_score, bonus_score, penalty_score, total_score, required_score,
                score_requirement_met, eligibility_status, potential_total_score, missing_inputs_json,
                needs_evidence_json, result_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_ITEM = """
            INSERT INTO e74_assessment_items (assessment_id, rule_code, category, status, earned_points,
                potential_points, message_code, evidence_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

    // e74_assessments·e74_assessment_items 의 ENUM(01_hanstep_schema.sql).
    // AI 의 EligibilityStatus·RuleCategory·ItemStatus 와 같은 값이다
    private static final Set<String> ELIGIBILITY_STATUSES = Set.of("ELIGIBLE", "INELIGIBLE", "NEEDS_EVIDENCE",
            "NEEDS_REVIEW");
    private static final Set<String> ITEM_CATEGORIES = Set.of("BASE_INCOME", "BASE_KOREAN", "BASE_AGE", "BONUS",
            "PENALTY", "REQUIREMENT", "EXCLUSION");
    private static final Set<String> ITEM_STATUSES = Set.of("MET", "NOT_MET", "NEEDS_EVIDENCE", "NEEDS_REVIEW",
            "NOT_APPLICABLE");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AiE74Client aiE74Client;
    private final E74ProfileSnapshotReader snapshotReader;
    private final UserRepository userRepository;
    private final E74Properties properties;

    public E74AssessmentService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                AiE74Client aiE74Client, E74ProfileSnapshotReader snapshotReader,
                                UserRepository userRepository, E74Properties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.aiE74Client = aiE74Client;
        this.snapshotReader = snapshotReader;
        this.userRepository = userRepository;
        this.properties = properties;
    }

    /** created=false 면 같은 requestKey 의 저장된 결과를 돌려준 것(200, 새 행 없음). */
    public record Result(E74AssessmentResponse body, boolean created) {
    }

    /** 채팅 도구용 내부 API. 없는 사용자면 404. */
    public Result assessUser(Long userId, LocalDate asOfDate, JsonNode scenario, LegalDeclaration legalDeclaration) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return assess(userId, asOfDate, scenario, legalDeclaration);
    }

    /** asOfDate 가 없으면 오늘(KST). */
    public Result assess(Long userId, LocalDate asOfDate, JsonNode scenario, LegalDeclaration legalDeclaration) {
        JsonNode scenarioNode = scenario == null || scenario.isNull() ? null : scenario;
        if (scenarioNode != null && !scenarioNode.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    Map.of("fields", List.of(Map.of("field", "scenario", "reason", "must be an object"))));
        }
        ObjectNode profile = snapshotReader.read(userId);
        if (legalDeclaration == null) {
            profile.putNull("legalDeclaration");
        } else {
            profile.set("legalDeclaration", MAPPER.valueToTree(legalDeclaration));
        }
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("asOfDate", (asOfDate != null ? asOfDate : LocalDate.now(KstDates.KST)).toString());
        request.set("profile", profile);
        if (scenarioNode == null) {
            request.putNull("scenario");
        } else {
            request.set("scenario", scenarioNode);
        }

        JsonNode response;
        try {
            response = aiE74Client.calculate(request);
        } catch (AiValidationException e) {
            List<Map<String, String>> fields = e.getFields();
            // 호출자가 보낸 값(scenario, legalDeclaration)만 걸렸으면 400. 나머지는 MySQL 에서 만든 프로필이
            // AI 제약(현재 근무처 1개, 코드 형식 등)에 걸린 것이라 요청을 고쳐도 안 되므로 422
            boolean callerInput = !fields.isEmpty()
                    && fields.stream().allMatch(field -> isCallerField(field.get("field")));
            log.warn("e74 calculate rejected the request: userId={} callerInput={} fields={}",
                    userId, callerInput, fields);
            if (callerInput) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("fields", fields));
            }
            throw new BusinessException(ErrorCode.INSUFFICIENT_DATA, "stored profile was rejected by the calculator",
                    Map.of("reason", "PROFILE_REJECTED", "fields", fields));
        } catch (AiCallException e) {
            log.warn("e74 calculate failed: userId={} errorCode={}", userId, e.getErrorCode(), e);
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }

        // 기준일에 맞는 규칙 버전이 없으면 rule_set_id(NOT NULL)를 채울 수 없어 저장하지 않는다
        String ruleVersion = text(response.path("ruleVersion"));
        if (ruleVersion == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "no applicable E-7-4 rule set",
                    Map.of("reason", "NO_APPLICABLE_RULE_SET"));
        }
        RuleSet ruleSet = ruleSet(ruleVersion).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE,
                "E-7-4 rule set is not registered", Map.of("reason", "RULE_SET_NOT_REGISTERED", "ruleVersion", ruleVersion)));
        // AI 가 승인 전 규칙으로 계산해 와도(AI 쪽 설정) 여기서 한 번 더 막는다. 저장된 결과를 재사용할 때도 같다
        if (!usable(ruleSet.approvalStatus())) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "E-7-4 rule set is not approved",
                    Map.of("reason", "RULE_SET_NOT_APPROVED", "ruleVersion", ruleVersion));
        }
        String requestKey = response.path("requestKey").stringValue();
        Optional<E74AssessmentResponse> existing = findByRequestKey(userId, requestKey);
        if (existing.isPresent()) {
            return new Result(existing.get(), false);
        }
        // ENUM·NOT NULL·UNIQUE 에 맞지 않으면 저장할 수 없다. AI 응답이 약속과 다른 것이라 502
        String mismatch = schemaMismatch(response);
        if (mismatch != null) {
            log.warn("e74 calculate result does not fit the tables: userId={} field={}", userId, mismatch);
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
        try {
            Long assessmentId = transactionTemplate.execute(status -> insert(userId, ruleSet.id(), request, response));
            return new Result(new E74AssessmentResponse(assessmentId, false, response), true);
        } catch (DuplicateKeyException e) {
            // 같은 requestKey 가 동시에 들어오면 먼저 저장된 행을 돌려준다
            return new Result(findByRequestKey(userId, requestKey).orElseThrow(() -> e), false);
        }
    }

    /** 최신 1건(scenario=false 면 일반 계산만). 없으면 404. */
    public E74AssessmentDetailResponse latest(Long userId, boolean scenario) {
        return jdbcTemplate.query("""
                                SELECT id, created_at, result_json FROM e74_assessments
                                WHERE user_id = ? AND is_scenario = ? ORDER BY id DESC LIMIT 1""",
                        E74AssessmentService::detail, userId, scenario)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 본인 행만. 남의 id 도 404(있는지 알려 주지 않음). */
    public E74AssessmentDetailResponse get(Long userId, Long assessmentId) {
        return jdbcTemplate.query("SELECT id, created_at, result_json FROM e74_assessments WHERE id = ? AND user_id = ?",
                        E74AssessmentService::detail, assessmentId, userId)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Optional<E74AssessmentResponse> findByRequestKey(Long userId, String requestKey) {
        return jdbcTemplate.query("SELECT id, result_json FROM e74_assessments WHERE user_id = ? AND request_key = ?",
                        (rs, i) -> new E74AssessmentResponse(rs.getLong("id"), true, json(rs.getString("result_json"))),
                        userId, requestKey)
                .stream().findFirst();
    }

    /** e74_rule_sets 한 행. approvalStatus 는 DRAFT·APPROVED·RETIRED. */
    private record RuleSet(long id, String approvalStatus) {
    }

    private Optional<RuleSet> ruleSet(String ruleVersion) {
        return jdbcTemplate.query("SELECT id, approval_status FROM e74_rule_sets WHERE rule_version = ?",
                (rs, i) -> new RuleSet(rs.getLong("id"), rs.getString("approval_status")), ruleVersion)
                .stream().findFirst();
    }

    // APPROVED 만. DRAFT 는 시연 설정일 때만, RETIRED 는 언제나 안 된다(AI 도 고르지 않음)
    private boolean usable(String approvalStatus) {
        return "APPROVED".equals(approvalStatus) || (properties.includeDraftRules() && "DRAFT".equals(approvalStatus));
    }

    private Long insert(Long userId, Long ruleSetId, JsonNode request, JsonNode response) {
        JsonNode score = response.path("score");
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_ASSESSMENT, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setLong(2, ruleSetId);
            ps.setString(3, response.path("requestKey").stringValue());
            ps.setObject(4, LocalDate.parse(request.path("asOfDate").stringValue()));
            ps.setBoolean(5, response.path("isScenario").asBoolean(false));
            ps.setString(6, jsonText(response.path("scenario")));
            ps.setString(7, MAPPER.writeValueAsString(request));
            setSmallint(ps, 8, score.path("base"));
            setSmallint(ps, 9, score.path("bonus"));
            setSmallint(ps, 10, score.path("penalty"));
            setSmallint(ps, 11, score.path("total"));
            setSmallint(ps, 12, score.path("required"));
            JsonNode met = response.path("scoreRequirementMet");
            if (met.isBoolean()) {
                ps.setBoolean(13, met.booleanValue());
            } else {
                ps.setNull(13, Types.BOOLEAN);
            }
            ps.setString(14, response.path("status").stringValue());
            setSmallint(ps, 15, response.path("potentialScore").path("total"));
            ps.setString(16, jsonText(response.path("missingInputs")));
            ps.setString(17, jsonText(response.path("needsEvidence")));
            ps.setString(18, MAPPER.writeValueAsString(response));
            return ps;
        }, keyHolder);
        long assessmentId = keyHolder.getKey().longValue();

        List<Object[]> items = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            items.add(new Object[] {assessmentId, ruleCode(item), item.path("category").stringValue(),
                    item.path("status").stringValue(), integer(item.path("earnedPoints")),
                    integer(item.path("potentialPoints")), text(item.path("messageCode")),
                    jsonText(item.path("evidence"))});
        }
        if (!items.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_ITEM, items);
        }
        return assessmentId;
    }

    // 저장할 수 없는 응답이면 걸린 필드 경로(로그용), 맞으면 null
    private static String schemaMismatch(JsonNode response) {
        if (!oneOf(response.path("status"), ELIGIBILITY_STATUSES)) {
            return "status";
        }
        JsonNode items = response.path("items");
        Set<String> ruleCodes = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!oneOf(item.path("category"), ITEM_CATEGORIES)) {
                return "items." + i + ".category";
            }
            if (!oneOf(item.path("status"), ITEM_STATUSES)) {
                return "items." + i + ".status";
            }
            // UNIQUE(assessment_id, rule_code)
            if (!ruleCodes.add(ruleCode(item))) {
                return "items." + i + ".ruleCode";
            }
        }
        return null;
    }

    private static boolean oneOf(JsonNode value, Set<String> allowed) {
        return value.isString() && allowed.contains(value.stringValue());
    }

    // 입력이 부족하거나 점수표 밖이면 기본항목(소득·한국어·나이)이 ruleCode 없이 온다. category 로 채운다
    // (지금 AI 는 분류마다 하나만 만든다. 겹치면 schemaMismatch 가 막는다)
    private static String ruleCode(JsonNode item) {
        String ruleCode = text(item.path("ruleCode"));
        return ruleCode != null ? ruleCode : item.path("category").stringValue();
    }

    private static E74AssessmentDetailResponse detail(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
        OffsetDateTime kst = createdAt == null ? null
                : createdAt.toInstant(ZoneOffset.UTC).atOffset(KST).truncatedTo(ChronoUnit.SECONDS);
        return new E74AssessmentDetailResponse(rs.getLong("id"), kst, json(rs.getString("result_json")));
    }

    // AI 오류의 필드 경로(예: scenario.addEvidences.0.evidenceType) 중 호출자가 본문으로 보낸 부분
    private static boolean isCallerField(String path) {
        return path != null && (path.equals("scenario") || path.startsWith("scenario.")
                || path.equals("profile.legalDeclaration") || path.startsWith("profile.legalDeclaration."));
    }

    private static void setSmallint(PreparedStatement ps, int index, JsonNode value) throws SQLException {
        if (value.isIntegralNumber()) {
            ps.setInt(index, value.intValue());
        } else {
            ps.setNull(index, Types.SMALLINT);
        }
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
