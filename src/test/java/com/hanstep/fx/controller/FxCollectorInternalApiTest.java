package com.hanstep.fx.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.InternalApiTestSupport;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.time.KstDates;
import com.hanstep.fx.FakeFxSourceServer;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * POST /internal/v1/fx/backfill. 원천은 FakeFxSourceServer, 실제 DB, 테스트마다 롤백.
 * to 는 오늘 이후를 받지 않아 2001년(1/8 월요일)을 쓴다. 실데이터·04 시드와 키가 겹치지 않는다.
 */
@SpringBootTest(properties = InternalApiTestSupport.KEY_PROPERTY)
@AutoConfigureMockMvc
@Transactional
class FxCollectorInternalApiTest {

    private static final String URL = "/internal/v1/fx/backfill";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        FakeFxSourceServer.register(registry);
    }

    @BeforeEach
    void setUp() {
        FakeFxSourceServer.reset();
    }

    @Test
    void 기간을_주면_원천별_결과를_준다() throws Exception {
        backfill("from=2001-01-08&to=2001-01-12")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2001-01-08"))
                .andExpect(jsonPath("$.to").value("2001-01-12"))
                .andExpect(jsonPath("$.elapsedMs").isNumber())
                .andExpect(jsonPath("$.sources[0].source").value("MARKET_REF"))
                .andExpect(jsonPath("$.sources[0].provider").value("frankfurter-ecb"))
                .andExpect(jsonPath("$.sources[0].currencies[0].currency").value("PHP"))
                .andExpect(jsonPath("$.sources[0].currencies[0].inserted").value(5))
                .andExpect(jsonPath("$.sources[1].source").value("KOREAEXIM"))
                .andExpect(jsonPath("$.sources[1].status").value("OK"))
                .andExpect(jsonPath("$.sources[1].calls").value(5))
                .andExpect(jsonPath("$.sources[1].currencies[0].currency").value("USD"))
                .andExpect(jsonPath("$.sources[1].currencies[0].inserted").value(5))
                .andExpect(jsonPath("$.sources[1].nextFrom").isEmpty());
    }

    @Test
    void source로_한_원천만_돌린다() throws Exception {
        backfill("from=2001-01-08&to=2001-01-12&source=KOREAEXIM&maxCalls=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].source").value("KOREAEXIM"))
                .andExpect(jsonPath("$.sources[0].calls").value(2))
                .andExpect(jsonPath("$.sources[0].nextFrom").value("2001-01-10"));
    }

    @Test
    void 기간이_뒤집혔거나_미래거나_호출_상한이_범위_밖이면_400() throws Exception {
        LocalDate tomorrow = LocalDate.now(KstDates.KST).plusDays(1);
        backfill("from=2026-11-10&to=2026-11-09")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("from"));
        backfill("to=" + tomorrow)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("to"));
        backfill("from=2026-11-09&to=2026-11-10&maxCalls=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("maxCalls"));
        backfill("from=2026-13-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        backfill("source=MOCK")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 내부_토큰이_없으면_401() throws Exception {
        mockMvc.perform(post(URL + "?from=2001-01-08&to=2001-01-12")).andExpect(status().isUnauthorized());
    }

    private ResultActions backfill(String query) throws Exception {
        return mockMvc.perform(post(URL + "?" + query)
                .header(InternalTokenFilter.HEADER, InternalApiTestSupport.API_KEY));
    }
}
