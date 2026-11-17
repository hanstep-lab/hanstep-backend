package com.hanstep.product.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.hanstep.product.dto.ProductRecommendResponse.ExcludedCount;
import com.hanstep.product.dto.ProductRecommendResponse.Product;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * PRD-02 금융상품 추천 POST /internal/v1/products/recommend 호출. 형식은 AI 레포 ai/specs/products/recommend_api_v1.md
 * (app/products/screen.py) 기준. AI 는 모르는 필드가 있으면 400 이다.
 * 실패(연결·타임아웃·오류 상태·형식 오류)는 모두 AiCallException 으로 던진다. 테스트에서는 이 빈을 바꿔 끼운다.
 */
@Component
public class AiProductRecommendClient {

    static final String PATH = "/internal/v1/products/recommend";

    private final RestClient aiRestClient;

    public AiProductRecommendClient(@Qualifier("aiRestClient") RestClient aiRestClient) {
        this.aiRestClient = aiRestClient;
    }

    /** asOf·rankedBy·products·excludedSummary 나 상품의 id·이름·종류가 빠진 응답도 AI_BAD_RESPONSE. */
    public RecommendResponse recommend(RecommendRequest request) {
        RecommendResponse response;
        try {
            response = aiRestClient.post().uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(RecommendResponse.class);
        } catch (RestClientResponseException e) {
            throw new AiCallException("AI_HTTP_" + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            // 연결 실패·타임아웃
            throw new AiCallException("AI_UNREACHABLE", e);
        } catch (RestClientException e) {
            throw new AiCallException("AI_BAD_RESPONSE", e);
        }
        if (response == null || !response.complete()) {
            throw new AiCallException("AI_BAD_RESPONSE", null);
        }
        return response;
    }

    /** 요청 본문. profile·monthlySpareKrw·queryKo 는 null 도 보낸다(AI 기본값이 null). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecommendRequest(LocalDate asOf, Profile profile, Long monthlySpareKrw, Conditions conditions,
                                   String queryKo) {
    }

    /** product-profile(INT-19)과 같은 이름·null 규칙. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Profile(String visaTypeCode, Integer age, LocalDate stayExpiresOn) {
    }

    /** 넘긴 조건만 보낸다. AI 는 requireForeignerExplicit 에 null 을 받지 않으므로 null 은 빼고 보낸다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Conditions(String productType, Integer maxTermMonths, Long monthlyAmountKrw, String accrual,
                             Boolean requireForeignerExplicit) {
    }

    /** 응답 200. remainingStayMonths·abstainReason 만 null 일 수 있다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecommendResponse(LocalDate asOf, Integer remainingStayMonths, String rankedBy, String abstainReason,
                                    List<Product> products, List<ExcludedCount> excludedSummary) {

        boolean complete() {
            return asOf != null && rankedBy != null && products != null && excludedSummary != null
                    && products.stream().allMatch(p -> p != null && p.productId() != null && p.nameKo() != null
                    && p.kind() != null);
        }
    }

    /** 호출 실패. errorCode 는 로그용(AI_HTTP_502, AI_UNREACHABLE, AI_BAD_RESPONSE). */
    public static class AiCallException extends RuntimeException {

        private final String errorCode;

        public AiCallException(String errorCode, Throwable cause) {
            super("product recommend call failed: " + errorCode, cause);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
