package com.hanstep.transfer.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.transfer.dto.RemittanceQuoteResponse;
import com.hanstep.transfer.dto.TransferContextResponse;
import com.hanstep.transfer.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transfer", description = "해외송금 화면 조회·견적 (TR-001, 송금 실행 없음)")
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @Operation(summary = "해외송금 화면 조회 (TRF-01)",
            description = "받는 분(관계 확인한 행만, recipientId 없으면 어머니 우선)·받는 분 목록·출금 계좌·송금 목적·"
                    + "금액 빠른 선택. 송금 목적·빠른 선택·관계/나라 문구는 임시값.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "받는 분이 없음(RESOURCE_NOT_FOUND), "
            + "recipientId 가 내 받는 분이 아님(RECIPIENT_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_DATA: 출금 계좌 없음(details.reason=NO_ACCOUNT)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/context")
    public TransferContextResponse context(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "받는 분 ID(선택)", example = "1") @RequestParam(required = false) Long recipientId) {
        return transferService.context(userId, recipientId);
    }

    @Operation(summary = "해외송금 견적 (TRF-02)",
            description = "currency=KRW 면 amount 는 보낼 원화(받을 외화는 1단위 버림), currency=받는 분 통화면 amount 는 "
                    + "받을 외화(보낼 원화는 원 단위 올림). 환율은 받는 분 통화의 최신 매매기준율(참고값). "
                    + "보낼 원화가 출금 계좌 잔액보다 크면 422.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: amount 가 1 미만·소수·누락, "
            + "currency 가 KRW·받는 분 통화가 아님(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "내 받는 분이 아님(RECIPIENT_NOT_FOUND), "
            + "받는 분 통화의 환율 없음(FX_RATE_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_BALANCE: 보낼 원화가 잔액 초과"
            + "(details.sendAmount·balance), INSUFFICIENT_DATA: 출금 계좌 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/quote")
    public RemittanceQuoteResponse quote(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "금액(정수, 1 이상)", example = "500000") @RequestParam long amount,
            @Parameter(description = "amount 의 통화(KRW 또는 받는 분 통화)", example = "KRW") @RequestParam String currency,
            @Parameter(description = "받는 분 ID", example = "1") @RequestParam long recipientId) {
        return transferService.quote(userId, recipientId, amount, currency);
    }
}
