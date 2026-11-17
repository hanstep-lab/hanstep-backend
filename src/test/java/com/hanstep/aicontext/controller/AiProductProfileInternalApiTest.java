package com.hanstep.aicontext.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import java.time.LocalDate;
import java.time.Period;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** INT-19. 데모 사용자(Reyes) 값, 비자 프로필 없음, 인증·없는 사용자, 생일 전날·당일 만 나이. 테스트마다 롤백. */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class AiProductProfileInternalApiTest {

    private static final String URL = "/internal/v1/ai-context/users/%d/product-profile";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private long userId;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO users (external_user_key, display_name) VALUES ('test-product-profile', 'TEST')");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = 'test-product-profile'",
                Long.class);
    }

    @Test
    void Reyes는_E9_만료일_만_나이를_주고_생년월일은_없다() throws Exception {
        long reyesId = jdbc.queryForObject("SELECT id FROM users WHERE external_user_key = 'demo-e9-ph-reyes'",
                Long.class);
        LocalDate today = LocalDate.now(KstDates.KST);

        call(reyesId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visaTypeCode").value("E-9"))
                .andExpect(jsonPath("$.stayExpiresOn").value("2027-10-04"))
                .andExpect(jsonPath("$.age").value(Period.between(LocalDate.of(1995, 8, 15), today).getYears()))
                .andExpect(jsonPath("$.asOf").value(today.toString()))
                .andExpect(jsonPath("$.birthDate").doesNotExist())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void 비자_프로필이_없으면_200에_세_값_null() throws Exception {
        call(userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visaTypeCode").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.stayExpiresOn").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.age").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.asOf").value(LocalDate.now(KstDates.KST).toString()));
    }

    @Test
    void 생년월일만_없으면_age만_null() throws Exception {
        visaProfile(null);

        call(userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visaTypeCode").value("E-9"))
                .andExpect(jsonPath("$.stayExpiresOn").value("2027-10-04"))
                .andExpect(jsonPath("$.age").value(Matchers.nullValue()));
    }

    @Test
    void 생일_당일에_한_살_늘어난다() throws Exception {
        visaProfile(LocalDate.now(KstDates.KST).minusYears(30));

        call(userId).andExpect(status().isOk()).andExpect(jsonPath("$.age").value(30));
    }

    @Test
    void 생일_전날까지는_한_살_적다() throws Exception {
        visaProfile(LocalDate.now(KstDates.KST).minusYears(30).plusDays(1));

        call(userId).andExpect(status().isOk()).andExpect(jsonPath("$.age").value(29));
    }

    @Test
    void 토큰이_없거나_틀리면_401() throws Exception {
        mockMvc.perform(get(URL.formatted(userId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(URL.formatted(userId)).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY + "x"))
                .andExpect(status().isUnauthorized());
        // 앱 JWT 로는 내부 API 를 부를 수 없다
        mockMvc.perform(get(URL.formatted(userId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(userId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 없는_사용자는_404_USER_NOT_FOUND() throws Exception {
        call(999_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    private ResultActions call(long id) throws Exception {
        return mockMvc.perform(get(URL.formatted(id)).header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY));
    }

    private void visaProfile(LocalDate birthDate) {
        jdbc.update("INSERT INTO visa_profiles (user_id, visa_type_id, stay_expires_on, birth_date) "
                + "SELECT ?, id, '2027-10-04', ? FROM visa_types WHERE code = 'E-9'", userId, birthDate);
    }
}
