package com.hanstep.transfer.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.transfer.dto.RemittanceQuoteResponse;
import com.hanstep.transfer.dto.TransferContextResponse;
import com.hanstep.transfer.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transfer", description = "해외송금 화면 조회·견적 (TR-001, 송금 실행 없음)")
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    // Reyes 700,000원 견적 응답(로컬 실제 출력, 2026-11-18). 환율은 수출입은행 USD 2026-11-17·ECB PHP 2026-11-17 실데이터,
    // 1년 송금 횟수는 Mock 거래 22건 + 이번 1건
    static final String QUOTE_EXAMPLE = """
            {
              "recipientId": 1,
              "sendAmount": {
                "amount": 700000,
                "currency": "KRW"
              },
              "receiveAmount": {
                "amount": 33025,
                "currency": "PHP"
              },
              "appliedRate": {
                "currency": "PHP",
                "unit": 1,
                "rate": 20.984,
                "sourceLabel": "ECB 기준환율 기반 참고값",
                "asOf": "2026-11-17",
                "isMock": false
              },
              "isReference": true,
              "referenceNotice": "LOCAL_PAYOUT_RATE_DIFFERS",
              "sendCurrency": "USD",
              "defaultProduct": "SOL_EASY",
              "isEstimated": true,
              "rates": {
                "usdBaseRate": 1335,
                "usdRateDate": "2026-11-17",
                "usdSource": "KOREAEXIM",
                "phpRefRate": 20.984,
                "phpRateDate": "2026-11-17",
                "phpSource": "frankfurter-ecb",
                "usdPhpRefRate": 63.6199
              },
              "quotes": [
                {
                  "product": "SOL_EASY",
                  "available": true,
                  "unavailableReason": null,
                  "sendAmountUsd": 519.11,
                  "spreadPct": 0.954,
                  "preferentialPct": 70,
                  "remittanceCount1y": 23,
                  "usdAppliedRate": 1338.82,
                  "krwPrincipal": 694995,
                  "fxMarginKrw": 1983,
                  "sendFeeKrw": 0,
                  "telegraphicFeeKrw": 5000,
                  "feeUsd": null,
                  "totalWithdrawKrw": 699995,
                  "recipientAmountUsd": 519.11,
                  "recipientAmountPhpRef": 33025.73,
                  "deliveryType": "SWIFT_ACCOUNT",
                  "notices": [
                    "INTERMEDIARY_FEE_MAY_APPLY",
                    "LOCAL_PAYOUT_RATE_DIFFERS",
                    "FOREIGN_EXCHANGE_BANK_DESIGNATION_REQUIRED"
                  ],
                  "sources": [
                    {
                      "item": "usdBaseRate",
                      "title": "한국수출입은행 현재환율 Open API 매매기준율(USD)",
                      "approvalNo": null,
                      "checkedOn": "2026-11-17"
                    },
                    {
                      "item": "spreadPct",
                      "title": "신한은행 국제환율 고시표 2026-11-18 09:07 11회차(매매기준율 1,331.50 / 보내실 때 1,344.20)",
                      "approvalNo": null,
                      "checkedOn": "2026-11-18"
                    },
                    {
                      "item": "preferentialPct",
                      "title": "신한 SOL 쏠편한 해외송금 안내",
                      "approvalNo": "준법감시인 사전심사필 제2026-14794-1호(2026.09.16~2027.09.15)",
                      "checkedOn": "2026-11-18"
                    },
                    {
                      "item": "remittanceCount1y",
                      "title": "마이데이터 거래내역 해외송금 건수(Mock)",
                      "approvalNo": null,
                      "checkedOn": "2026-11-18"
                    },
                    {
                      "item": "sendFeeKrw",
                      "title": "신한 SOL 쏠편한 해외송금 안내",
                      "approvalNo": "준법감시인 사전심사필 제2026-14794-1호(2026.09.16~2027.09.15)",
                      "checkedOn": "2026-11-18"
                    },
                    {
                      "item": "telegraphicFeeKrw",
                      "title": "신한 SOL 쏠편한 해외송금 안내",
                      "approvalNo": "준법감시인 사전심사필 제2026-14794-1호(2026.09.16~2027.09.15)",
                      "checkedOn": "2026-11-18"
                    },
                    {
                      "item": "phpRefRate",
                      "title": "Frankfurter v2 ECB 기준환율(PHP)",
                      "approvalNo": null,
                      "checkedOn": "2026-11-17"
                    }
                  ]
                },
                {
                  "product": "SOL_FAST",
                  "available": true,
                  "unavailableReason": null,
                  "sendAmountUsd": 515.88,
                  "spreadPct": 0.954,
                  "preferentialPct": 0,
                  "remittanceCount1y": null,
                  "usdAppliedRate": 1347.74,
                  "krwPrincipal": 695273,
                  "fxMarginKrw": 6572,
                  "sendFeeKrw": null,
                  "telegraphicFeeKrw": null,
                  "feeUsd": 3.5,
                  "totalWithdrawKrw": 699990,
                  "recipientAmountUsd": 515.88,
                  "recipientAmountPhpRef": 32820.23,
                  "deliveryType": "WESTERN_UNION",
                  "notices": [
                    "LOCAL_PAYOUT_RATE_BY_AGENT",
                    "LOCAL_PAYOUT_RATE_DIFFERS",
                    "FOREIGN_EXCHANGE_BANK_DESIGNATION_REQUIRED"
                  ],
                  "sources": [
                    {
                      "item": "usdBaseRate",
                      "title": "한국수출입은행 현재환율 Open API 매매기준율(USD)",
                      "approvalNo": null,
                      "checkedOn": "2026-11-17"
                    },
                    {
                      "item": "spreadPct",
                      "title": "신한은행 국제환율 고시표 2026-11-18 09:07 11회차(매매기준율 1,331.50 / 보내실 때 1,344.20)",
                      "approvalNo": null,
                      "checkedOn": "2026-11-18"
                    },
                    {
                      "item": "feeUsd",
                      "title": "신한 쏠빠른 해외송금 상품설명서·거래약관(제9조)",
                      "approvalNo": "준법감시인 사전심사필 제2026-12828-2호(2026.06.17~2027.06.16)",
                      "checkedOn": "2026-11-18"
                    },
                    {
                      "item": "perTransferLimitUsd",
                      "title": "신한 쏠빠른 해외송금 상품설명서·거래약관(제9조)",
                      "approvalNo": "준법감시인 사전심사필 제2026-12828-2호(2026.06.17~2027.06.16)",
                      "checkedOn": "2026-11-18"
                    },
                    {
                      "item": "phpRefRate",
                      "title": "Frankfurter v2 ECB 기준환율(PHP)",
                      "approvalNo": null,
                      "checkedOn": "2026-11-17"
                    }
                  ]
                }
              ]
            }""";

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

    static final String QUOTE_DESCRIPTION = """
            amount 는 내 계좌에서 나갈 원화 총액(수수료 포함)이고 currency 는 KRW 만 받는다.
            송금 통화는 USD 다(신한 SOL 해외송금은 필리핀을 골라도 송금 통화에 PHP 가 없음). 신한 쏠편한 해외송금(기본)과
            쏠빠른 해외송금(비교)을 같이 계산한 추정값이다(isEstimated=true). 공시값과 출처는 quotes[].sources 에 있다.
            - 적용 송금 환율 = USD 매매기준율(수출입은행 고시) × (1 + 스프레드 × (1 − 우대율)), 소수 2자리 반올림
            - 쏠편한: 송금 USD = (amount − 송금수수료 − 전신료) ÷ 적용 환율. 우대율은 1년 송금 횟수(이번 건 포함) 구간
            - 쏠빠른: 송금 USD = amount ÷ 적용 환율 − 3.5. 우대 없음, 건당 USD 5,000 초과면 LIMIT_EXCEEDED
            - 송금 USD 는 0.01 내림, 원화는 원 단위 올림. 수수료를 빼고 0 이하면 AMOUNT_TOO_SMALL
            - 쏠편한 수수료 기준(USD 5,000) 사이에 걸리는 금액이면 USD 5,000.00 까지만 보내고, 남는 원화를 unusedKrw 로 주며 \
            AMOUNT_CAPPED_AT_FEE_BOUNDARY 를 붙인다
            - fxMarginKrw(환전 마진)는 krwPrincipal 에 이미 포함된 참고값이라 합산하지 않는다
            수취 국가는 사용자 국적(visa_profiles)으로 정하고, 필리핀이면 PHP 참고값을 붙인다.
            receiveAmount·appliedRate 는 기존 이름·PHP 기준 그대로인 참고값이다(쏠편한 기준, 실제 수령액과 다를 수 있음).
            송금에 쓰는 환율은 quotes[].usdAppliedRate 다. 중계·수취은행 수수료와 현지 환전은 금액 없이 notices 로만 안내한다.""";

    @Operation(summary = "해외송금 견적 (TRF-02)", description = QUOTE_DESCRIPTION
            + " recipientId 를 빼면 첫 받는 분(어머니 먼저, TRF-01 과 같음). 출금 총액(amount)이 잔액보다 크면 422.")
    @ApiResponse(responseCode = "200", description = "성공(상품별 available·unavailableReason 으로 보낼 수 있는지 본다)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = RemittanceQuoteResponse.class),
                    examples = @ExampleObject(name = "Reyes 700,000원", value = QUOTE_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: amount 가 1 미만·소수·누락, currency 가 KRW 가 아님"
            + "(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "받는 분이 없음(RESOURCE_NOT_FOUND), 내 받는 분이 아님(RECIPIENT_NOT_FOUND), "
            + "USD 매매기준율 실데이터 없음(FX_RATE_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_BALANCE: amount 가 잔액 초과"
            + "(details.sendAmount·balance), INSUFFICIENT_DATA: 출금 계좌 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/quote")
    public RemittanceQuoteResponse quote(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "내 계좌에서 나갈 원화 총액(수수료 포함, 정수, 1 이상)", example = "700000")
            @RequestParam long amount,
            @Parameter(description = "amount 의 통화. KRW 만", example = "KRW") @RequestParam String currency,
            @Parameter(description = "받는 분 ID(선택). 없으면 첫 받는 분", example = "1")
            @RequestParam(required = false) Long recipientId) {
        return transferService.quote(userId, recipientId, amount, currency);
    }
}
