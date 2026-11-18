package com.hanstep.fx.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 로컬 개발용 설정 hanstep.fx-rates.include-mock=true 면 Mock 환율도 읽는다. 운영 기본값은 false. */
@SpringBootTest(properties = {"hanstep.fx-rates.include-mock=true",
        "hanstep.fx-rates.home-currencies[0].nationality=XTL", "hanstep.fx-rates.home-currencies[0].currency=XTS"})
@AutoConfigureMockMvc
@Transactional
class FxRateMockIncludedTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String bearer;

    @BeforeEach
    void setUp() {
        long userId = TestUsers.create(jdbc, "test-fx-rates-mock", "TEST FX RATES MOCK");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
        jdbc.update("INSERT INTO visa_profiles (user_id, nationality_code) VALUES (?, 'XTL')", userId);
    }

    @Test
    void Mock_만_있으면_Mock_을_isMock_true로_준다() throws Exception {
        insert("MOCK", true, "2099-03-31", "1300.0000");

        mockMvc.perform(get("/api/v1/fx/rates").param("currency", "XTS").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MOCK"))
                .andExpect(jsonPath("$.isMock").value(true))
                .andExpect(jsonPath("$.sourceLabel").value("Mock 환율(로컬 개발용)"));
    }

    @Test
    void 실데이터가_있으면_Mock_보다_우선한다() throws Exception {
        insert("MOCK", true, "2099-04-01", "1300.0000");
        insert("KOREAEXIM", false, "2099-03-31", "1200.0000");

        mockMvc.perform(get("/api/v1/fx/rates").param("currency", "XTS").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.source").value("KOREAEXIM"))
                .andExpect(jsonPath("$.rate").value(1200))
                .andExpect(jsonPath("$.isMock").value(false));
    }

    private void insert(String source, boolean mock, String rateDate, String base) {
        jdbc.update("INSERT INTO exchange_rates (currency, unit_amount, rate_date, rate_at, base_rate, source, is_mock, "
                + "fetched_at) VALUES ('XTS', 1, ?, CONCAT(?, ' 00:00:00'), ?, ?, ?, NOW(3))",
                rateDate, rateDate, base, source, mock);
    }
}
