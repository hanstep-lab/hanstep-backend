package com.hanstep.onboarding.controller;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
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

/**
 * 온보딩 API 계약 확인. 인증·형식·오류 코드가 명세대로인지 확인.
 * ONB-01~03 은 실제 DB(데모 사용자)로 돌므로 필요한 상태를 테스트 안에서 만들고, 끝나면 롤백함.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OnboardingContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String bearer;

    @BeforeEach
    void setUp() {
        bearer = "Bearer " + jwtTokenProvider.issue(1L);
    }

    @Test
    void 토큰_없이_호출하면_401() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 온보딩_진행_상태는_6단계를_반환() throws Exception {
        mockMvc.perform(put("/api/v1/onboarding/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"residencePurpose\":\"WORK\",\"declaredMonthlyIncome\":{\"amount\":2350000,"
                                + "\"currency\":\"KRW\"}}"))
                .andExpect(status().isOk());
        finish("LANGUAGE", "COMPLETED");

        mockMvc.perform(get("/api/v1/onboarding").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(6))
                .andExpect(jsonPath("$.steps[0].stepCode").value("LANGUAGE"))
                .andExpect(jsonPath("$.steps[0].completedAt").value(
                        matchesPattern("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+]09:00")))
                .andExpect(jsonPath("$.profile.residencePurpose").value("WORK"))
                .andExpect(jsonPath("$.profile.declaredMonthlyIncome.amount").value(2350000));
    }

    @Test
    void 단계_저장은_COMPLETED_SKIPPED만_허용() throws Exception {
        finish("LANGUAGE", "COMPLETED");
        finish("ID_DOCUMENT", "COMPLETED");

        mockMvc.perform(post("/api/v1/onboarding/steps/MYDATA_CONSENT")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SKIPPED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SKIPPED"))
                .andExpect(jsonPath("$.currentStep").value("GOALS"));

        mockMvc.perform(post("/api/v1/onboarding/steps/MYDATA_CONSENT")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 없는_단계_코드는_400() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/steps/NOPE")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("stepCode"));
    }

    @Test
    void 마이데이터_연결은_Idempotency_Key가_없으면_400() throws Exception {
        String body = "{\"institutionIds\":[1,2],\"scopes\":[\"ACCOUNT_BALANCE\",\"TRANSACTIONS\"]}";
        mockMvc.perform(post("/api/v1/mydata/connections")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("Idempotency-Key"));

        // MYD-02 는 MYDATA·MYDATA_TERMS 동의가 있어야 함(없으면 403). 롤백되므로 실제 데이터는 그대로
        for (String type : new String[] {"MYDATA", "MYDATA_TERMS"}) {
            jdbcTemplate.update("INSERT INTO user_consents (user_id, consent_type, policy_version, status, granted_at) "
                    + "VALUES (1, ?, 'test', 'GRANTED', NOW(3))", type);
        }
        mockMvc.perform(post("/api/v1/mydata/connections")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", "test-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connections[0].isMock").value(true));
    }

    @Test
    void 필수_확보액_저장은_금액_형식을_검사() throws Exception {
        mockMvc.perform(put("/api/v1/commitments")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"commitmentType\":\"VARIABLE_EXPENSE\","
                                + "\"amount\":{\"amount\":-1,\"currency\":\"KRW\"}}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(put("/api/v1/commitments")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"commitmentType\":\"VARIABLE_EXPENSE\","
                                + "\"amount\":{\"amount\":450000,\"currency\":\"KRW\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.variableExpense.amount").value(450000));
    }

    private void finish(String stepCode, String stepStatus) throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/steps/" + stepCode)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + stepStatus + "\"}"))
                .andExpect(status().isOk());
    }
}
