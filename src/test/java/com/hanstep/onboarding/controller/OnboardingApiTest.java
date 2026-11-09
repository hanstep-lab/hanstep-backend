package com.hanstep.onboarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.onboarding.repository.OnboardingProfileRepository;
import com.hanstep.onboarding.repository.OnboardingProgressRepository;
import com.hanstep.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** ONB-01 ~ 04. 실제 DB(데모 사용자 demo-e9-nguyen), 테스트마다 롤백. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OnboardingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OnboardingProgressRepository progressRepository;

    @Autowired
    private OnboardingProfileRepository profileRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        userId = userRepository.findByExternalUserKey("demo-e9-nguyen").orElseThrow().getId();
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        // 롤백되므로 실제 데이터는 그대로
        progressRepository.deleteAll(progressRepository.findAllByUserId(userId));
        profileRepository.findByUserId(userId).ifPresent(profileRepository::delete);
        progressRepository.flush(); // 같은 단계를 다시 INSERT 할 때 UNIQUE 충돌이 없도록 DELETE 를 먼저 반영
    }

    @Test
    void 기록이_없어도_6단계를_NOT_STARTED로_반환() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.currentStep").value("LANGUAGE"))
                .andExpect(jsonPath("$.steps.length()").value(6))
                .andExpect(jsonPath("$.steps[5].stepCode").value("SUMMARY_CONFIRM"))
                .andExpect(jsonPath("$.steps[5].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.profile.residencePurpose").isEmpty());
    }

    @Test
    void 체류_목적만_보내면_저장되고_월소득은_null() throws Exception {
        mockMvc.perform(put("/api/v1/onboarding/profile").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"residencePurpose\":\"WORK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.residencePurpose").value("WORK"))
                .andExpect(jsonPath("$.declaredMonthlyIncome").isEmpty());

        mockMvc.perform(get("/api/v1/onboarding").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.profile.residencePurpose").value("WORK"))
                .andExpect(jsonPath("$.profile.declaredMonthlyIncome").isEmpty());
    }

    @Test
    void 프로필은_1인_1행으로_덮어쓴다() throws Exception {
        putProfile("{\"residencePurpose\":\"WORK\",\"declaredMonthlyIncome\":{\"amount\":2350000,"
                + "\"currency\":\"KRW\"}}");
        putProfile("{\"residencePurpose\":\"FAMILY\"}");

        mockMvc.perform(get("/api/v1/onboarding").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.profile.residencePurpose").value("FAMILY"))
                .andExpect(jsonPath("$.profile.declaredMonthlyIncome").isEmpty());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM onboarding_profiles WHERE user_id = ?", Long.class, userId)).isEqualTo(1);
    }

    @Test
    void 단계를_끝내면_다음_단계를_돌려주고_DB에는_UTC로_저장() throws Exception {
        String body = mockMvc.perform(post("/api/v1/onboarding/steps/LANGUAGE")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("ID_DOCUMENT"))
                .andExpect(jsonPath("$.onboardingCompleted").value(false))
                .andReturn().getResponse().getContentAsString();

        OffsetDateTime responded = OffsetDateTime.parse(
                objectMapper.readTree(body).path("completedAt").asString());
        LocalDateTime stored = jdbcTemplate.queryForObject(
                "SELECT completed_at FROM onboarding_progress WHERE user_id = ? AND step_code = 'LANGUAGE'",
                LocalDateTime.class, userId);
        assertThat(responded.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(stored).isEqualTo(responded.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
    }

    @Test
    void 최종_확인은_등록증_확정_전이면_visa와_국적이_null() throws Exception {
        jdbcTemplate.update("DELETE FROM visa_profiles WHERE user_id = ?", userId);

        mockMvc.perform(get("/api/v1/onboarding/summary").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId))
                .andExpect(jsonPath("$.user.nationalityCode").isEmpty())
                .andExpect(jsonPath("$.visa").isEmpty())
                .andExpect(jsonPath("$.balance").isEmpty());
    }

    @Test
    void 최종_확인은_체류_정보_목표_필수_확보액을_DB에서_읽는다() throws Exception {
        LocalDate date = LocalDate.now(KstDates.KST).plusMonths(10).plusDays(1);
        Long e9 = jdbcTemplate.queryForObject("SELECT id FROM visa_types WHERE code = 'E-9'", Long.class);
        jdbcTemplate.update("DELETE FROM visa_profiles WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM financial_goals WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM monthly_commitments WHERE user_id = ?", userId);
        jdbcTemplate.update("INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, nationality_code) "
                + "VALUES (?, ?, ?, 'VNM')", userId, e9, date);
        jdbcTemplate.update("INSERT INTO financial_goals (user_id, goal_type, target_amount, target_date) "
                + "VALUES (?, 'LUMP_SUM', 6000000, ?)", userId, date);
        jdbcTemplate.update("INSERT INTO financial_goals (user_id, goal_type) VALUES (?, 'REMITTANCE')", userId);
        jdbcTemplate.update("INSERT INTO monthly_commitments (user_id, commitment_type, label, amount) "
                + "VALUES (?, 'RENT', '월세', 400000), (?, 'REMITTANCE', '가족 송금', 700000)", userId, userId);

        mockMvc.perform(get("/api/v1/onboarding/summary").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.nationalityCode").value("VNM"))
                .andExpect(jsonPath("$.visa.visaTypeCode").value("E-9"))
                .andExpect(jsonPath("$.visa.stayExpiresOn").value(date.toString()))
                .andExpect(jsonPath("$.visa.monthsRemaining").value(10))
                .andExpect(jsonPath("$.goals.length()").value(2))
                .andExpect(jsonPath("$.goals[0].goalType").value("LUMP_SUM"))
                .andExpect(jsonPath("$.goals[0].targetAmount.amount").value(6000000))
                .andExpect(jsonPath("$.goals[0].monthlyRequired.amount").value(600000))
                .andExpect(jsonPath("$.goals[1].goalType").value("REMITTANCE"))
                .andExpect(jsonPath("$.goals[1].monthlyRequired").isEmpty())
                .andExpect(jsonPath("$.commitments.items.length()").value(2))
                .andExpect(jsonPath("$.commitments.totals.fixedLiving.amount").value(400000))
                .andExpect(jsonPath("$.commitments.totals.remittance.amount").value(700000))
                .andExpect(jsonPath("$.balance").isEmpty());
    }

    @Test
    void 최종_확인은_토큰_없으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private void putProfile(String json) throws Exception {
        mockMvc.perform(put("/api/v1/onboarding/profile").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }
}
