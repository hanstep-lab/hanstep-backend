package com.hanstep.finance.client;

import com.hanstep.common.dto.Money;
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
 * INT-01 POST /internal/v1/finance/analyze 호출. 형식은 AI 레포 app/finance/schemas.py 기준.
 * 실패는 모두 AiCallException(errorCode 포함)으로 바꿔 던진다. 테스트에서는 이 빈을 바꿔 끼운다.
 */
@Component
public class AiFinanceClient {

    static final String PATH = "/internal/v1/finance/analyze";

    private final RestClient aiRestClient;

    public AiFinanceClient(@Qualifier("aiRestClient") RestClient aiRestClient) {
        this.aiRestClient = aiRestClient;
    }

    public AnalyzeResponse analyze(AnalyzeRequest request) {
        AnalyzeResponse response;
        try {
            response = aiRestClient.post().uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AnalyzeResponse.class);
        } catch (RestClientResponseException e) {
            throw new AiCallException("AI_HTTP_" + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            // 연결 실패·타임아웃
            throw new AiCallException("AI_UNREACHABLE", e);
        } catch (RestClientException e) {
            throw new AiCallException("AI_BAD_RESPONSE", e);
        }
        if (response == null || response.dataQuality() == null) {
            throw new AiCallException("AI_BAD_RESPONSE", null);
        }
        return response;
    }

    /** INT-01 요청. 이름·식별번호 없이 userId 숫자만 보낸다. */
    public record AnalyzeRequest(Long userId, LocalDate periodFrom, LocalDate periodTo, List<Transaction> transactions) {
    }

    /** amount 는 원 단위 양의 정수. categoryCode·transactionKind·direction 은 DB 저장값 그대로. */
    public record Transaction(Long id, LocalDate bookedDate, String direction, long amount, String categoryCode,
                              String transactionKind) {
    }

    /** 꽉 찬 달이 없으면 금액은 모두 null(warnings 에 NO_FULL_MONTH). */
    public record AnalyzeResponse(Money avgMonthlyIncome, Money fixedLiving, Money remittance,
                                  VariableExpense variableExpense, Money spare, DataQuality dataQuality,
                                  List<Excluded> excluded) {
    }

    public record VariableExpense(Money avg, Money min, Money max) {
    }

    public record DataQuality(int observedMonthCount, List<String> missingMonths, List<String> partialMonths,
                              List<String> warnings) {
    }

    public record Excluded(Long transactionId, String reason) {
    }

    /** INT-01 호출 실패. errorCode 는 financial_analyses.error_code(50자 이내)에 저장한다. */
    public static class AiCallException extends RuntimeException {

        private final String errorCode;

        public AiCallException(String errorCode, Throwable cause) {
            super("INT-01 call failed: " + errorCode, cause);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
