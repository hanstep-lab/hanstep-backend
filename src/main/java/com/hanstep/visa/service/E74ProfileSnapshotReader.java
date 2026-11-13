package com.hanstep.visa.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI E-7-4 계산 입력 profile 을 MySQL 에서 만든다(visaProfile·employmentHistories·incomeRecords·visaEvidences).
 * 필드 이름은 AI 레포 app/visa/e74/schemas.py 기준(컬럼 이름의 camelCase), 금액은 원 단위 정수.
 * legalDeclaration 은 저장하지 않는 값이라 여기서 넣지 않는다.
 * AI 의 requestKey 가 입력 해시라서, 같은 데이터면 같은 JSON 이 나오도록 목록 순서를 고정한다.
 */
@Component
public class E74ProfileSnapshotReader {

    private final JdbcTemplate jdbcTemplate;

    public E74ProfileSnapshotReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** AI 입력 profile 과 체류 만료일(visa_profiles.stay_expires_on, 없으면 null). 만료일은 AI E74Profile 에 없어 따로 둔다. */
    public record Snapshot(ObjectNode profile, LocalDate stayExpiresOn) {
    }

    /** 체류 프로필(OCR 확정값)이 없으면 422 INSUFFICIENT_DATA(reason NO_VISA_PROFILE). */
    public ObjectNode read(Long userId) {
        return readWithStay(userId).profile();
    }

    /** read 와 같은 profile 에 체류 만료일을 함께 읽는다(로드맵 API 요청에 필요). */
    public Snapshot readWithStay(Long userId) {
        List<Snapshot> visaProfiles = jdbcTemplate.query("""
                        SELECT vt.code AS visa_type_code, vp.birth_date, vp.evidence_status,
                               vp.current_stay_started_on, vp.prior_stay_months, vp.stay_expires_on
                        FROM visa_profiles vp LEFT JOIN visa_types vt ON vt.id = vp.visa_type_id
                        WHERE vp.user_id = ?""",
                (rs, i) -> {
                    ObjectNode node = JsonNodeFactory.instance.objectNode();
                    node.put("visaTypeCode", rs.getString("visa_type_code"));
                    node.put("birthDate", date(rs, "birth_date"));
                    node.put("evidenceStatus", rs.getString("evidence_status"));
                    node.put("currentStayStartedOn", date(rs, "current_stay_started_on"));
                    node.put("priorStayMonths", rs.getObject("prior_stay_months", Integer.class));
                    ObjectNode profile = JsonNodeFactory.instance.objectNode();
                    profile.set("visaProfile", node);
                    return new Snapshot(profile, rs.getObject("stay_expires_on", LocalDate.class));
                },
                userId);
        if (visaProfiles.isEmpty()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_DATA, Map.of("reason", "NO_VISA_PROFILE"));
        }

        Snapshot snapshot = visaProfiles.get(0);
        ObjectNode profile = snapshot.profile();
        employmentHistories(userId, profile.putArray("employmentHistories"));
        incomeRecords(userId, profile.putArray("incomeRecords"));
        visaEvidences(userId, profile.putArray("visaEvidences"));
        return snapshot;
    }

    private void employmentHistories(Long userId, ArrayNode list) {
        jdbcTemplate.query("""
                        SELECT id, employer_name, industry_code, workplace_region_code, is_depopulation_area, started_on,
                               ended_on, contract_end_on, contract_annual_salary, left_without_fault, verification_status
                        FROM employment_histories WHERE user_id = ? ORDER BY id""",
                rs -> {
                    ObjectNode node = list.addObject();
                    node.put("employmentHistoryId", rs.getLong("id"));
                    node.put("employerName", rs.getString("employer_name"));
                    node.put("industryCode", rs.getString("industry_code"));
                    node.put("workplaceRegionCode", rs.getString("workplace_region_code"));
                    node.put("isDepopulationArea", rs.getObject("is_depopulation_area", Boolean.class));
                    node.put("startedOn", date(rs, "started_on"));
                    node.put("endedOn", date(rs, "ended_on"));
                    node.put("contractEndOn", date(rs, "contract_end_on"));
                    node.put("contractAnnualSalary", won(rs, "contract_annual_salary"));
                    node.put("leftWithoutFault", rs.getObject("left_without_fault", Boolean.class));
                    node.put("verificationStatus", rs.getString("verification_status"));
                },
                userId);
    }

    private void incomeRecords(Long userId, ArrayNode list) {
        jdbcTemplate.query("""
                        SELECT income_year, annual_amount, source, verification_status
                        FROM income_records WHERE user_id = ? ORDER BY income_year, source""",
                rs -> {
                    ObjectNode node = list.addObject();
                    node.put("incomeYear", rs.getInt("income_year"));
                    node.put("annualAmount", won(rs, "annual_amount"));
                    node.put("source", rs.getString("source"));
                    node.put("verificationStatus", rs.getString("verification_status"));
                },
                userId);
    }

    private void visaEvidences(Long userId, ArrayNode list) {
        jdbcTemplate.query("""
                        SELECT id, evidence_type, level_value, issued_on, expires_on, status, pre_test_score,
                               pre_test_assigned_stage, related_to_current_job
                        FROM visa_evidence WHERE user_id = ? ORDER BY id""",
                rs -> {
                    ObjectNode node = list.addObject();
                    node.put("visaEvidenceId", rs.getLong("id"));
                    node.put("evidenceType", rs.getString("evidence_type"));
                    node.put("levelValue", rs.getString("level_value"));
                    node.put("issuedOn", date(rs, "issued_on"));
                    node.put("expiresOn", date(rs, "expires_on"));
                    node.put("status", rs.getString("status"));
                    node.put("preTestScore", rs.getObject("pre_test_score", Integer.class));
                    node.put("preTestAssignedStage", rs.getObject("pre_test_assigned_stage", Integer.class));
                    node.put("relatedToCurrentJob", rs.getObject("related_to_current_job", Boolean.class));
                },
                userId);
    }

    private static String date(ResultSet rs, String column) throws SQLException {
        LocalDate value = rs.getObject(column, LocalDate.class);
        return value == null ? null : value.toString();
    }

    // DECIMAL 금액을 원 단위 정수로. 소수가 있으면 데이터 오류로 본다(금융 분석과 같은 규칙)
    private static Long won(ResultSet rs, String column) throws SQLException {
        BigDecimal amount = rs.getBigDecimal(column);
        if (amount == null) {
            return null;
        }
        try {
            return amount.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException(column + " is not whole KRW", e);
        }
    }
}
