package com.hanstep.product.service;

import com.hanstep.aicontext.dto.AiProductProfileResponse;
import com.hanstep.aicontext.service.AiContextService;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.finance.dto.MonthlyBalanceResponse;
import com.hanstep.finance.service.MonthlyBalanceService;
import com.hanstep.product.client.AiProductRecommendClient;
import com.hanstep.product.client.AiProductRecommendClient.AiCallException;
import com.hanstep.product.client.AiProductRecommendClient.Conditions;
import com.hanstep.product.client.AiProductRecommendClient.Profile;
import com.hanstep.product.client.AiProductRecommendClient.RecommendRequest;
import com.hanstep.product.client.AiProductRecommendClient.RecommendResponse;
import com.hanstep.product.dto.ProductRecommendConditions;
import com.hanstep.product.dto.ProductRecommendResponse;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * PRD-02 금융상품 추천. 프로필(INT-19 product-profile)과 월 여유자금(MonthlyBalanceService, ONB-04·INT-06 balance 와 같은 값)을
 * 모아 AI 추천기(LLM 아님, 챗봇 상품 추천과
 * 같은 추천기)를 부르고 응답을 그대로 옮긴다. 금리·특징은 받은 값만 옮기고, 기권(products 빈 목록 + abstainReason)도 200 으로
 * 그대로 준다. 저장하지 않는다.
 */
@Service
public class ProductRecommendService {

    private static final Logger log = LoggerFactory.getLogger(ProductRecommendService.class);

    private final AiContextService aiContextService;
    private final MonthlyBalanceService monthlyBalanceService;
    private final AiProductRecommendClient aiProductRecommendClient;

    public ProductRecommendService(AiContextService aiContextService, MonthlyBalanceService monthlyBalanceService,
                                   AiProductRecommendClient aiProductRecommendClient) {
        this.aiContextService = aiContextService;
        this.monthlyBalanceService = monthlyBalanceService;
        this.aiProductRecommendClient = aiProductRecommendClient;
    }

    /** 조건 범위 밖은 400, 사용자가 없으면 404 USER_NOT_FOUND, AI 호출 실패(타임아웃 포함)는 502 AI_UPSTREAM_ERROR. */
    public ProductRecommendResponse recommend(Long userId, ProductRecommendConditions conditions) {
        validate(conditions);
        AiProductProfileResponse profile = aiContextService.productProfile(userId);
        RecommendRequest request = new RecommendRequest(profile.asOf(),
                new Profile(profile.visaTypeCode(), profile.age(), profile.stayExpiresOn()),
                monthlySpare(userId), toAi(conditions), null);

        RecommendResponse response;
        try {
            response = aiProductRecommendClient.recommend(request);
        } catch (AiCallException e) {
            log.warn("product recommend failed: userId={} errorCode={}", userId, e.getErrorCode(), e);
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
        return new ProductRecommendResponse(response.asOf(), response.remainingStayMonths(), response.rankedBy(),
                response.abstainReason(), response.products(), response.excludedSummary(), true);
    }

    /**
     * 월 여유자금(INT-06 balance.spare.amount·ONB-04 balance.spare.amount 와 같은 값). 분석 결과가 없거나 금액이 없으면 null
     * (AI 는 여유자금 조건을 판정 불가로 둔다). 음수(지출이 소득보다 많음)는 0 으로 보낸다: AI 계약이 0 이상만 받고,
     * 추천기는 여유자금을 적금 월 최소 납입액·요청 월 납입액(모두 1원 이상)과만 비교하므로 음수와 0 의 판정이 같다.
     */
    Long monthlySpare(Long userId) {
        MonthlyBalanceResponse balance = monthlyBalanceService.balance(userId);
        return balance == null ? null : Math.max(balance.spare().amount(), 0L);
    }

    private static Conditions toAi(ProductRecommendConditions c) {
        return new Conditions(c.productType() == null ? null : c.productType().name(), c.maxTermMonths(),
                c.monthlyAmountKrw(), c.accrual() == null ? null : c.accrual().name(), c.requireForeignerExplicit());
    }

    private static void validate(ProductRecommendConditions c) {
        Integer term = c.maxTermMonths();
        if (term != null && (term < 1 || term > ProductRecommendConditions.MAX_TERM_MONTHS)) {
            throw invalid("maxTermMonths", "must be between 1 and " + ProductRecommendConditions.MAX_TERM_MONTHS);
        }
        Long amount = c.monthlyAmountKrw();
        if (amount != null && (amount < 1 || amount > ProductRecommendConditions.MAX_MONTHLY_AMOUNT_KRW)) {
            throw invalid("monthlyAmountKrw",
                    "must be between 1 and " + ProductRecommendConditions.MAX_MONTHLY_AMOUNT_KRW);
        }
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, null,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
