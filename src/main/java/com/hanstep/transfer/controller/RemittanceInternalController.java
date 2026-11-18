package com.hanstep.transfer.controller;

import com.hanstep.common.config.OpenApiConfig;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.error.ErrorResponse;
import com.hanstep.transfer.dto.AiRemittanceRecipientListResponse;
import com.hanstep.transfer.dto.AppliedRate;
import com.hanstep.transfer.dto.RemittanceQuoteResponse;
import com.hanstep.transfer.service.RemittanceQuoteService;
import com.hanstep.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 챗봇 송금 조회 도구용(계약: hanstep-AI ai/specs/chat/remittance_contract_v1.md). 조회만, 송금 실행 없음. */
@Tag(name = "Internal · AI Context", description = "AI → Spring 컨텍스트 조회 (X-Internal-Token)")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN)
@RestController
@RequestMapping("/internal/v1/ai-context")
public class RemittanceInternalController {

    private final RemittanceQuoteService quoteService;
    private final UserRepository userRepository;

    public RemittanceInternalController(RemittanceQuoteService quoteService, UserRepository userRepository) {
        this.quoteService = quoteService;
        this.userRepository = userRepository;
    }

    @Operation(summary = "AI 용 송금 받는 분 목록 (INT-17)",
            description = "관계를 확인한 활성 받는 분(어머니 먼저). 화면 TRF-01 과 같은 조건. 계좌번호는 끝 4자리만.")
    @ApiResponse(responseCode = "200", description = "성공. 없으면 recipients=[]")
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없는 사용자(RESOURCE_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/users/{userId}/remittance-recipients")
    public AiRemittanceRecipientListResponse recipients(@PathVariable Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return AiRemittanceRecipientListResponse.from(quoteService.recipients(userId));
    }

    @Operation(summary = "AI 용 참고 환율 (INT-14)",
            description = "그 통화의 최신 매매기준율(exchange_rates, 참고값). unit 외화 = rate 원.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "400", description = "currency 누락(VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "그 통화의 환율 없음(FX_RATE_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/fx-rates")
    public AppliedRate fxRate(@Parameter(description = "통화(ISO 4217)", example = "PHP") @RequestParam String currency) {
        return quoteService.latestRate(currency);
    }

    @Operation(summary = "AI 용 송금 견적 (INT-18)", description = TransferController.QUOTE_DESCRIPTION
            + " 화면 TRF-02 와 같은 요청·계산·응답이다(계산 코드 하나). 잔액은 확인하지 않는다.")
    @ApiResponse(responseCode = "200", description = "성공",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = RemittanceQuoteResponse.class),
                    examples = @ExampleObject(name = "Reyes 700,000원", value = TransferController.QUOTE_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: amount 가 1 미만·소수·누락, currency 가 KRW 가 아님"
            + "(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "그 사용자의 저장된 받는 분이 아님(RECIPIENT_NOT_FOUND), "
            + "USD 매매기준율 실데이터 없음(FX_RATE_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/users/{userId}/remittance-quote")
    public RemittanceQuoteResponse quote(
            @PathVariable Long userId,
            @Parameter(description = "받는 분 ID", example = "1") @RequestParam long recipientId,
            @Parameter(description = "내 계좌에서 나갈 원화 총액(수수료 포함, 정수, 1 이상)", example = "700000")
            @RequestParam long amount,
            @Parameter(description = "amount 의 통화. KRW 만", example = "KRW") @RequestParam String currency) {
        return quoteService.quote(userId, recipientId, amount, currency);
    }
}
