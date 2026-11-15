package com.hanstep.goal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.support.TestUsers;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** GOL-01 ~ 02. 실제 DB(테스트 전용 사용자), 테스트마다 롤백. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GoalApiTest {

    private static final String LUMP_SUM = "{\"goalType\":\"LUMP_SUM\",\"targetAmount\":{\"amount\":6000000,"
            + "\"currency\":\"KRW\"},\"targetDate\":\"2027-10-04\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        userId = TestUsers.create(jdbcTemplate, "test-gol-api", "TEST GOL USER");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    @Test
    void 전체_교체하면_빠진_목표는_CANCELLED로_남는다() throws Exception {
        save("[" + LUMP_SUM + ",{\"goalType\":\"REMITTANCE\"},{\"goalType\":\"LIVING_COST\"}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals.length()").value(3));
        save("[" + LUMP_SUM + ",{\"goalType\":\"REMITTANCE\"}]").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/goals").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals.length()").value(2))
                .andExpect(jsonPath("$.goals[0].goalType").value("LUMP_SUM"))
                .andExpect(jsonPath("$.goals[0].targetAmount.amount").value(6000000))
                .andExpect(jsonPath("$.goals[0].targetDate").value("2027-10-04"));
        assertThat(jdbcTemplate.queryForList(
                "SELECT CONCAT(goal_type, ':', status) FROM financial_goals WHERE user_id = ? ORDER BY id",
                String.class, userId))
                .containsExactly("LUMP_SUM:ACTIVE", "REMITTANCE:ACTIVE", "LIVING_COST:CANCELLED");
    }

    @Test
    void 목표_금액과_목표일이_있으면_월_필요액을_계산하고_없으면_null() throws Exception {
        LocalDate date = LocalDate.now(KstDates.KST).plusMonths(10).plusDays(1);
        save("[{\"goalType\":\"LUMP_SUM\",\"targetAmount\":{\"amount\":6000000,\"currency\":\"KRW\"},"
                + "\"targetDate\":\"" + date + "\"},{\"goalType\":\"REMITTANCE\"}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goals[0].monthlyRequired.amount").value(600000))
                .andExpect(jsonPath("$.goals[0].monthlyRequired.currency").value("KRW"))
                .andExpect(jsonPath("$.goals[1].monthlyRequired").isEmpty());
    }

    @Test
    void goalType_중복은_400() throws Exception {
        save("[{\"goalType\":\"REMITTANCE\"},{\"goalType\":\"REMITTANCE\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("goals[1].goalType"));
    }

    @Test
    void 목표_금액이_0_이하면_400() throws Exception {
        save("[{\"goalType\":\"LUMP_SUM\",\"targetAmount\":{\"amount\":0,\"currency\":\"KRW\"}}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private ResultActions save(String goalsJson) throws Exception {
        return mockMvc.perform(put("/api/v1/goals").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goals\":" + goalsJson + "}"));
    }
}
