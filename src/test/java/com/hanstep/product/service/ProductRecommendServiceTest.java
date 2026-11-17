package com.hanstep.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hanstep.aicontext.dto.AiProductProfileResponse;
import com.hanstep.aicontext.service.AiContextService;
import com.hanstep.common.dto.Money;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.finance.dto.FinanceAnalysisResponse;
import com.hanstep.finance.service.FinanceAnalysisService;
import com.hanstep.product.client.AiProductRecommendClient;
import com.hanstep.product.client.AiProductRecommendClient.AiCallException;
import com.hanstep.product.client.AiProductRecommendClient.Conditions;
import com.hanstep.product.client.AiProductRecommendClient.Profile;
import com.hanstep.product.client.AiProductRecommendClient.RecommendRequest;
import com.hanstep.product.client.AiProductRecommendClient.RecommendResponse;
import com.hanstep.product.dto.ProductRecommendConditions;
import com.hanstep.product.dto.ProductRecommendConditions.Accrual;
import com.hanstep.product.dto.ProductRecommendConditions.ProductType;
import com.hanstep.product.dto.ProductRecommendResponse;
import com.hanstep.product.dto.ProductRecommendResponse.AmountRange;
import com.hanstep.product.dto.ProductRecommendResponse.BaseRate;
import com.hanstep.product.dto.ProductRecommendResponse.Evidence;
import com.hanstep.product.dto.ProductRecommendResponse.ExcludedCount;
import com.hanstep.product.dto.ProductRecommendResponse.Feature;
import com.hanstep.product.dto.ProductRecommendResponse.MaxRate;
import com.hanstep.product.dto.ProductRecommendResponse.Product;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 프로필·여유자금을 모아 보내는 값, AI 응답을 옮기는 규칙(값 그대로·null 그대로·기권 전달), 오류 변환. */
class ProductRecommendServiceTest {

    private static final long USER_ID = 7L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 17);
    private static final ProductRecommendConditions NONE = new ProductRecommendConditions(null, null, null, null, null);

    private final AiContextService aiContextService = mock(AiContextService.class);
    private final FinanceAnalysisService financeAnalysisService = mock(FinanceAnalysisService.class);
    private final AiProductRecommendClient client = mock(AiProductRecommendClient.class);
    private final ProductRecommendService service = new ProductRecommendService(aiContextService,
            financeAnalysisService, client);

    @BeforeEach
    void setUp() {
        when(aiContextService.productProfile(USER_ID))
                .thenReturn(new AiProductProfileResponse("E-9", LocalDate.of(2027, 10, 4), 30, AS_OF));
        givenAnalysis(675_317L);
    }

    @Test
    void 프로필과_여유자금과_조건을_계약_이름으로_보낸다() {
        when(client.recommend(any())).thenReturn(response(null, List.of(deposit())));

        service.recommend(USER_ID, new ProductRecommendConditions(ProductType.SAVINGS, 12, 300_000L, Accrual.FLEXIBLE,
                true));

        assertThat(sent()).isEqualTo(new RecommendRequest(AS_OF, new Profile("E-9", 30, LocalDate.of(2027, 10, 4)),
                675_317L, new Conditions("SAVINGS", 12, 300_000L, "FLEXIBLE", true), null));
    }

    @Test
    void 프로필_값이_없으면_null로_보내고_분석_결과가_없으면_여유자금도_null() {
        when(aiContextService.productProfile(USER_ID)).thenReturn(new AiProductProfileResponse(null, null, null, AS_OF));
        when(financeAnalysisService.findLatestCompleted(USER_ID)).thenReturn(Optional.empty());
        when(client.recommend(any())).thenReturn(response(null, List.of(deposit())));

        service.recommend(USER_ID, NONE);

        assertThat(sent()).isEqualTo(new RecommendRequest(AS_OF, new Profile(null, null, null), null,
                new Conditions(null, null, null, null, null), null));
    }

    @Test
    void 여유자금_금액이_없으면_null_음수면_0으로_보낸다() {
        when(client.recommend(any())).thenReturn(response(null, List.of(deposit())));

        givenAnalysis(null);
        assertThat(service.monthlySpare(USER_ID)).isNull();

        givenAnalysis(-120_000L);
        assertThat(service.monthlySpare(USER_ID)).isZero();

        givenAnalysis(0L);
        assertThat(service.monthlySpare(USER_ID)).isZero();
    }

    @Test
    void 응답은_값을_그대로_옮기고_isMock을_붙인다() {
        Product deposit = deposit();
        RecommendResponse ai = response(null, List.of(deposit));
        when(client.recommend(any())).thenReturn(ai);

        ProductRecommendResponse result = service.recommend(USER_ID, NONE);

        assertThat(result.asOf()).isEqualTo(AS_OF);
        assertThat(result.remainingStayMonths()).isEqualTo(10);
        assertThat(result.rankedBy()).isEqualTo("catalog_no_query");
        assertThat(result.abstainReason()).isNull();
        assertThat(result.products()).containsExactly(deposit);
        assertThat(result.excludedSummary()).isEqualTo(ai.excludedSummary());
        assertThat(result.isMock()).isTrue();
        // 기간별 금리여도 원문 값 하나만, 더하거나 바꾸지 않는다
        assertThat(result.products().getFirst().baseRate().ratePct()).isEqualByComparingTo("3.3");
    }

    @Test
    void 금리와_특징이_null이면_채우지_않고_null_그대로() {
        Product noRate = new Product("doc-b89237ab049e", "신한 스마트 적금", "SAVINGS", Map.of("min", 12, "max", 12),
                new AmountRange(1_000L, "no limit"), null, "FLEXIBLE", null,
                List.of("visa", "age", "remainingStay", "spareMoney"), null, null, null, null, List.of());
        when(client.recommend(any())).thenReturn(response(null, List.of(noRate)));

        Product result = service.recommend(USER_ID, NONE).products().getFirst();

        assertThat(result.baseRate()).isNull();
        assertThat(result.maxRate()).isNull();
        assertThat(result.feature()).isNull();
        assertThat(result.stayFits()).isNull();
        assertThat(result.foreignerEligible()).isNull();
        assertThat(result).isEqualTo(noRate);
    }

    @Test
    void 기권은_빈_목록과_이유_코드를_그대로_준다() {
        when(client.recommend(any())).thenReturn(response("REQUEST_EXCEEDS_SURPLUS", List.of()));

        ProductRecommendResponse result = service.recommend(USER_ID,
                new ProductRecommendConditions(ProductType.SAVINGS, null, 1_000_000L, null, null));

        assertThat(result.products()).isEmpty();
        assertThat(result.abstainReason()).isEqualTo("REQUEST_EXCEEDS_SURPLUS");
        assertThat(result.isMock()).isTrue();
    }

    @Test
    void AI_호출_실패는_AI_UPSTREAM_ERROR() {
        when(client.recommend(any())).thenThrow(new AiCallException("AI_UNREACHABLE", null));

        assertThatThrownBy(() -> service.recommend(USER_ID, NONE))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UPSTREAM_ERROR));
    }

    @Test
    void 사용자가_없으면_USER_NOT_FOUND이고_AI를_부르지_않는다() {
        when(aiContextService.productProfile(USER_ID)).thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

        assertThatThrownBy(() -> service.recommend(USER_ID, NONE))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        verifyNoInteractions(client);
    }

    @Test
    void 조건_범위_밖은_VALIDATION_ERROR이고_AI를_부르지_않는다() {
        assertInvalid(new ProductRecommendConditions(null, 0, null, null, null), "maxTermMonths");
        assertInvalid(new ProductRecommendConditions(null, 121, null, null, null), "maxTermMonths");
        assertInvalid(new ProductRecommendConditions(null, null, 0L, null, null), "monthlyAmountKrw");
        assertInvalid(new ProductRecommendConditions(null, null, 100_000_001L, null, null), "monthlyAmountKrw");
        verify(client, never()).recommend(any());
    }

    private void assertInvalid(ProductRecommendConditions conditions, String field) {
        assertThatThrownBy(() -> service.recommend(USER_ID, conditions))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getDetails().get("fields").toString()).contains(field);
                });
    }

    private RecommendRequest sent() {
        ArgumentCaptor<RecommendRequest> captor = ArgumentCaptor.forClass(RecommendRequest.class);
        verify(client).recommend(captor.capture());
        return captor.getValue();
    }

    private static RecommendResponse response(String abstainReason, List<Product> products) {
        return new RecommendResponse(AS_OF, 10, "catalog_no_query", abstainReason, products,
                List.of(new ExcludedCount("recommendable", 1), new ExcludedCount("stayTerm", 5)));
    }

    static Product deposit() {
        return new Product("doc-a32e7bfdf57e", "슈퍼SOL 웰컴 정기예금", "DEPOSIT", List.of(6, 12), null,
                new AmountRange(500_000L, 20_000_000), null, "inferred", List.of("visa"), true,
                new BaseRate(new BigDecimal("3.3"), LocalDate.of(2026, 11, 9),
                        "기본이자율 (2026.11.09 현재, 세전) 6개월 : 연 3.3% 12개월 : 연 3.6%", 2),
                new MaxRate(new BigDecimal("3.6"), "최고이자율 연 3.6%", 2), new Feature("정기예금", 1),
                List.of(new Evidence("term", "계약기간 6개월, 12개월", 1)));
    }

    // 다른 when(...) 안에서 목을 만들면 Mockito 가 중첩 스터빙으로 실패하므로 먼저 만든다
    private void givenAnalysis(Long spare) {
        FinanceAnalysisResponse analysis = mock(FinanceAnalysisResponse.class);
        when(analysis.spare()).thenReturn(spare == null ? null : new Money(spare, Money.KRW));
        when(financeAnalysisService.findLatestCompleted(USER_ID)).thenReturn(Optional.of(analysis));
    }
}
