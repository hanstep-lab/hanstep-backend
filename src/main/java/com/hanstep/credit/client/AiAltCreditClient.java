package com.hanstep.credit.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.hanstep.credit.domain.AltCreditDecision;
import com.hanstep.credit.dto.AltCreditAssessmentResponse.RateBreakdown;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대안신용 모의 사전진단 POST /internal/v1/credit/alt-assessments 호출. 형식은 AI 레포 app/credit/schemas.py·alt_score.py 기준.
 * 400(입력 거부)은 AiValidationException 으로, 나머지 실패(연결·타임아웃·그 밖의 상태·형식 오류)는 AiCallException 으로 던진다
 * (E-7-4 계산 호출과 같은 규칙). 테스트에서는 이 빈을 바꿔 끼운다.
 */
@Component
public class AiAltCreditClient {

    static final String PATH = "/internal/v1/credit/alt-assessments";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final RestClient aiRestClient;

    public AiAltCreditClient(@Qualifier("aiRestClient") RestClient aiRestClient) {
        this.aiRestClient = aiRestClient;
    }

    /** 판정·모델 버전 등 필수 필드가 빠진 응답도 AI_BAD_RESPONSE. */
    public AssessResponse assess(AssessRequest request) {
        AssessResponse response;
        try {
            response = aiRestClient.post().uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AssessResponse.class);
        } catch (HttpClientErrorException.BadRequest e) {
            throw new AiValidationException(fields(e.getResponseBodyAsString()), e);
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

    // AI 오류 본문의 details.fields. AI 는 필드 경로와 사유만 주고 값은 되돌려 보내지 않는다
    private static List<Map<String, String>> fields(String body) {
        List<Map<String, String>> fields = new ArrayList<>();
        JsonNode error;
        try {
            error = body == null || body.isBlank() ? null : MAPPER.readTree(body);
        } catch (JacksonException e) {
            error = null;
        }
        if (error == null) {
            return fields;
        }
        for (JsonNode field : error.path("details").path("fields")) {
            fields.add(Map.of("field", field.path("field").asString("unknown"),
                    "reason", field.path("reason").asString("invalid")));
        }
        return fields;
    }

    /**
     * AI AltCreditRequest. AI 가 null 과 값 누락을 구분하므로(monthsToExpiry·remitMonths6m 은 기본값 없음) null 도 보낸다.
     * existingDebt·existingMonthlyPayment 는 보내지 않는다(AI 기본값 0).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AssessRequest(boolean isMock, int stayDurationMonths, Integer monthsToExpiry, long monthlyIncome,
                                boolean workPermitted, int salaryMonths, boolean shinhanPayroll,
                                boolean hasSubscription, boolean isRefinance, int telLate6m, int utilLate6m,
                                BigDecimal remitRatio, Integer remitMonths6m) {
    }

    /** AI AltCreditAssessment. rateBreakdown·maxLimitBinding·reasonCode 만 null 일 수 있다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssessResponse(String modelVersion, Boolean isMock, Double pd, Integer score,
                                 RateBreakdown rateBreakdown, LocalDate rateBaseDate, Integer loanTermMonths,
                                 Long minLimit, Long maxLimit, String maxLimitBinding, AltCreditDecision decision,
                                 String reasonCode, List<String> inputWarnings, String disclaimer) {

        boolean complete() {
            return modelVersion != null && isMock != null && pd != null && score != null && rateBaseDate != null
                    && loanTermMonths != null && minLimit != null && maxLimit != null && decision != null
                    && inputWarnings != null && disclaimer != null;
        }
    }

    /** 호출 실패. errorCode 는 로그용(AI_HTTP_502, AI_UNREACHABLE, AI_BAD_RESPONSE). */
    public static class AiCallException extends RuntimeException {

        private final String errorCode;

        public AiCallException(String errorCode, Throwable cause) {
            super("alt credit assessment call failed: " + errorCode, cause);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    /** AI 가 입력을 거부함(400). 학습 범위 밖 누적 체류(reason out_of_training_range) 등. fields 는 AI 가 준 필드 경로·사유. */
    public static class AiValidationException extends RuntimeException {

        private final List<Map<String, String>> fields;

        public AiValidationException(List<Map<String, String>> fields, Throwable cause) {
            super("alt credit assessment rejected the request", cause);
            this.fields = List.copyOf(fields);
        }

        public List<Map<String, String>> getFields() {
            return fields;
        }
    }
}
