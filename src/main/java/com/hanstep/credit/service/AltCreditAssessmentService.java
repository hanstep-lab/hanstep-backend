package com.hanstep.credit.service;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.time.KstDates;
import com.hanstep.consent.domain.ConsentType;
import com.hanstep.consent.service.ConsentService;
import com.hanstep.credit.client.AiAltCreditClient;
import com.hanstep.credit.client.AiAltCreditClient.AiCallException;
import com.hanstep.credit.client.AiAltCreditClient.AiValidationException;
import com.hanstep.credit.client.AiAltCreditClient.AssessRequest;
import com.hanstep.credit.client.AiAltCreditClient.AssessResponse;
import com.hanstep.credit.dto.AltCreditAssessmentResponse;
import com.hanstep.credit.dto.AltCreditInputs;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 대안신용 모의 사전진단(실제 대출 승인이나 신용점수가 아님). 계산은 AI(모델 V9, LLM 아님)가 하고, 여기서는 입력을 모아 보내고
 * 응답에 보낸 입력(inputs)과 Mock·임시값 입력 이름(mockFields), 표시용 필드(AltCreditDisplayRules)를 붙여 돌려준다.
 * 저장하지 않는다.
 * AI 오류 처리는 E-7-4 평가와 같다: 입력 거부(400)는 422 INSUFFICIENT_DATA, 그 밖의 실패(타임아웃 포함)는 502.
 */
@Service
public class AltCreditAssessmentService {

    /** inputs 중 Mock 거래·계좌와 시드 청구 납부 내역(임시값)에서 나온 입력. 입력이 모두 Mock 이라 isMock 도 항상 true. */
    public static final List<String> MOCK_FIELDS = List.of("monthlyIncome", "salaryMonths", "shinhanPayroll",
            "hasSubscription", "telLate6m", "utilLate6m", "remitRatio", "remitMonths6m");

    private static final Logger log = LoggerFactory.getLogger(AltCreditAssessmentService.class);

    private final ConsentService consentService;
    private final AltCreditInputReader inputReader;
    private final AiAltCreditClient aiAltCreditClient;

    public AltCreditAssessmentService(ConsentService consentService, AltCreditInputReader inputReader,
                                      AiAltCreditClient aiAltCreditClient) {
        this.consentService = consentService;
        this.inputReader = inputReader;
        this.aiAltCreditClient = aiAltCreditClient;
    }

    /** CREDIT_EVALUATION 동의가 없으면 403 CONSENT_REQUIRED(데이터를 읽기 전에 막는다). */
    public AltCreditAssessmentResponse assess(Long userId, boolean isRefinance) {
        consentService.requireGranted(userId, ConsentType.CREDIT_EVALUATION);
        LocalDate today = LocalDate.now(KstDates.KST);
        AltCreditInputs inputs = inputReader.read(userId, today, isRefinance);

        AssessResponse response;
        try {
            response = aiAltCreditClient.assess(request(inputs));
        } catch (AiValidationException e) {
            // 입력은 모두 저장된 값으로 만든 것이라(대환 여부는 true/false) 요청을 고쳐도 안 된다. E-7-4 의 PROFILE_REJECTED 와 같은 뜻
            log.warn("alt credit assessment rejected the inputs: userId={} fields={}", userId, e.getFields());
            throw new BusinessException(ErrorCode.INSUFFICIENT_DATA, "stored inputs were rejected by the calculator",
                    Map.of("reason", "INPUT_REJECTED", "fields", e.getFields()));
        } catch (AiCallException e) {
            log.warn("alt credit assessment failed: userId={} errorCode={}", userId, e.getErrorCode(), e);
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
        return new AltCreditAssessmentResponse(response.modelVersion(), response.isMock(), response.pd(),
                response.score(), response.rateBreakdown(), response.rateBaseDate(), response.loanTermMonths(),
                response.minLimit(), response.maxLimit(), response.maxLimitBinding(), response.decision(),
                response.reasonCode(), response.inputWarnings(), response.disclaimer(), inputs, MOCK_FIELDS,
                AltCreditDisplayRules.scoreBand(response.score()), AltCreditDisplayRules.factors(inputs),
                AltCreditDisplayRules.eligibilityChecks(inputs));
    }

    private static AssessRequest request(AltCreditInputs inputs) {
        return new AssessRequest(true, inputs.stayDurationMonths(), inputs.monthsToExpiry(), inputs.monthlyIncome(),
                inputs.workPermitted(), inputs.salaryMonths(), inputs.shinhanPayroll(), inputs.hasSubscription(),
                inputs.isRefinance(), inputs.telLate6m(), inputs.utilLate6m(), inputs.remitRatio(),
                inputs.remitMonths6m());
    }
}
