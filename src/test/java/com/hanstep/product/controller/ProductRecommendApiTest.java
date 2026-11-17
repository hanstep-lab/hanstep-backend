package com.hanstep.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanstep.common.security.JwtTokenProvider;
import com.hanstep.common.time.KstDates;
import com.hanstep.product.client.AiProductRecommendClient;
import com.hanstep.product.client.AiProductRecommendClient.AiCallException;
import com.hanstep.product.client.AiProductRecommendClient.Conditions;
import com.hanstep.product.client.AiProductRecommendClient.Profile;
import com.hanstep.product.client.AiProductRecommendClient.RecommendRequest;
import com.hanstep.product.client.AiProductRecommendClient.RecommendResponse;
import com.hanstep.product.dto.ProductRecommendResponse.AmountRange;
import com.hanstep.product.dto.ProductRecommendResponse.BaseRate;
import com.hanstep.product.dto.ProductRecommendResponse.Evidence;
import com.hanstep.product.dto.ProductRecommendResponse.ExcludedCount;
import com.hanstep.product.dto.ProductRecommendResponse.Product;
import com.hanstep.support.TestUsers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD-02 금융상품 추천 API. 테스트 사용자를 직접 넣고 AI 추천 호출은 가짜로 바꿔 끼운다. 테스트마다 롤백.
 * 매핑 규칙 자체는 ProductRecommendServiceTest, AI 요청·응답 형식은 AiProductRecommendClientTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductRecommendApiTest {

    private static final String URL = "/api/v1/finance/products/recommend";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AiProductRecommendClient aiProductRecommendClient;

    private LocalDate today;
    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(KstDates.KST);
        userId = TestUsers.create(jdbc, "test-product-recommend", "TEST PRODUCT RECOMMEND");
        bearer = "Bearer " + jwtTokenProvider.issue(userId);
    }

    @Test
    void 토큰이_없으면_401_공통_오류_형식() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.requestId").exists());

        verify(aiProductRecommendClient, never()).recommend(any());
    }

    @Test
    void 조건을_AI에_넘기고_응답을_그대로_옮기며_null_키도_둔다() throws Exception {
        when(aiProductRecommendClient.recommend(any())).thenReturn(new RecommendResponse(today, null,
                "catalog_no_query", null, List.of(savings()), List.of(new ExcludedCount("kind", 4))));

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer)
                        .param("productType", "SAVINGS").param("maxTermMonths", "12")
                        .param("accrual", "FLEXIBLE").param("requireForeignerExplicit", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value(today.toString()))
                .andExpect(jsonPath("$.remainingStayMonths").isEmpty())
                .andExpect(jsonPath("$.rankedBy").value("catalog_no_query"))
                .andExpect(jsonPath("$.abstainReason").isEmpty())
                .andExpect(jsonPath("$.isMock").value(true))
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].productId").value("doc-b89237ab049e"))
                .andExpect(jsonPath("$.products[0].termMonths.min").value(12))
                .andExpect(jsonPath("$.products[0].monthlyAmountKrw.max").value("no limit"))
                .andExpect(jsonPath("$.products[0].baseRate.ratePct").value(3.15))
                .andExpect(jsonPath("$.products[0].baseRate.asOf").value("2026-10-01"))
                // null 은 키를 두고 null 로 준다(화면에서 키가 늘 같음). 채우지 않는다
                .andExpect(jsonPath("$.products[0].maxRate").isEmpty())
                .andExpect(jsonPath("$.products[0].feature").isEmpty())
                .andExpect(jsonPath("$.products[0].depositAmountKrw").isEmpty())
                .andExpect(jsonPath("$.products[0].stayFits").isEmpty())
                .andExpect(jsonPath("$.products[0].notChecked[0]").value("visa"))
                .andExpect(jsonPath("$.products[0].evidence[0].about").value("term"))
                .andExpect(jsonPath("$.excludedSummary[0].reason").value("kind"))
                .andExpect(jsonPath("$.excludedSummary[0].count").value(4));

        // 비자 프로필·분석 결과가 없는 사용자: 프로필 값과 여유자금은 null
        ArgumentCaptor<RecommendRequest> captor = ArgumentCaptor.forClass(RecommendRequest.class);
        verify(aiProductRecommendClient).recommend(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new RecommendRequest(today, new Profile(null, null, null), null,
                new Conditions("SAVINGS", 12, null, "FLEXIBLE", false), null));
    }

    @Test
    void 응답_JSON에_null_키가_빠지지_않는다() throws Exception {
        when(aiProductRecommendClient.recommend(any())).thenReturn(new RecommendResponse(today, null,
                "catalog_no_query", null, List.of(savings()), List.of()));

        String body = mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"abstainReason\":null", "\"remainingStayMonths\":null", "\"maxRate\":null",
                "\"feature\":null", "\"depositAmountKrw\":null", "\"stayFits\":null");
    }

    @Test
    void 기권은_200으로_빈_목록과_이유_코드() throws Exception {
        when(aiProductRecommendClient.recommend(any())).thenReturn(new RecommendResponse(today, 10,
                "catalog_no_query", "STAY_TOO_SHORT", List.of(), List.of(new ExcludedCount("stayTerm", 12))));

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(0))
                .andExpect(jsonPath("$.abstainReason").value("STAY_TOO_SHORT"))
                .andExpect(jsonPath("$.isMock").value(true));
    }

    @Test
    void AI_호출_실패는_502_AI_UPSTREAM_ERROR() throws Exception {
        when(aiProductRecommendClient.recommend(any())).thenThrow(new AiCallException("AI_UNREACHABLE", null));

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UPSTREAM_ERROR"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void 사용자가_없으면_404_USER_NOT_FOUND() throws Exception {
        long missing = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1000 FROM users", Long.class);

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.issue(missing)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        verify(aiProductRecommendClient, never()).recommend(any());
    }

    @Test
    void 조건_값이_틀리면_400_VALIDATION_ERROR() throws Exception {
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer).param("productType", "LOAN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields[0].field").value("productType"));

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer).param("maxTermMonths", "121"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields[0].field").value("maxTermMonths"));

        verify(aiProductRecommendClient, never()).recommend(any());
    }

    private static Product savings() {
        return new Product("doc-b89237ab049e", "신한 스마트 적금", "SAVINGS", Map.of("min", 12, "max", 12),
                new AmountRange(1_000L, "no limit"), null, "FLEXIBLE", "inferred",
                List.of("visa", "age", "remainingStay", "spareMoney"), null,
                new BaseRate(new BigDecimal("3.15"), LocalDate.of(2026, 10, 1), "기본이자율 연 3.15%", 1),
                null, null, List.of(new Evidence("term", "계약기간 1년제", 1)));
    }
}
