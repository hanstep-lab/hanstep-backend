package com.hanstep.credit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/** AI 에 보낸 입력(이름·값이 AI 요청과 같음, isMock 제외)과 집계 기준일·기간. */
@Schema(name = "AltCreditInputs", description = "AI 에 보낸 입력 요약(이름·값은 AI 요청과 같음, isMock 제외)과 집계 기준")
public record AltCreditInputs(
        @Schema(description = "누적 체류 개월 = 현재 체류 시작일~asOfDate 의 꽉 찬 달 수 + 이전 체류 개월", example = "47")
        int stayDurationMonths,

        @Schema(description = "체류 만료까지 남은 개월(내림). 만료일이 없으면 null, 이미 지났으면 0", nullable = true,
                example = "10")
        Integer monthsToExpiry,

        @Schema(description = "근로 가능 여부. 체류자격 코드로 정함(E-9·E-7-4·H-2·F-2 는 true, 그 밖·없음은 false)",
                example = "true")
        boolean workPermitted,

        @Schema(description = "월소득(원) = 거래 집계 기간의 소득 합 ÷ 개월 수(반올림)", example = "2350000")
        long monthlyIncome,

        @Schema(description = "periodTo 의 달부터 거꾸로 센 연속 급여 입금 개월(최대 12)", example = "12")
        int salaryMonths,

        @Schema(description = "해지 안 된 신한은행 급여 계좌가 있는지", example = "true")
        boolean shinhanPayroll,

        @Schema(description = "청약 유지 여부. 해지 안 된 적금(INSTALLMENT) 계좌가 있으면 true(임시 기준)", example = "false")
        boolean hasSubscription,

        @Schema(description = "대환 여부(요청 파라미터 isRefinance 그대로)", example = "false")
        boolean isRefinance,

        @Schema(description = "최근 6개월(recentFrom~periodTo 청구 월) 중 통신비 연체가 있었던 달 수(0~6)", example = "0")
        int telLate6m,

        @Schema(description = "최근 6개월 중 건강보험료·공과금 연체가 있었던 달 수(0~6). 같은 달 여러 건이 늦어도 1",
                example = "0")
        int utilLate6m,

        @Schema(description = "소득 대비 해외송금 비율 = 집계 기간 송금 합 ÷ 소득 합(소수 4자리 반올림, 최대 1). "
                + "소득이 없으면 0", example = "0.2979")
        BigDecimal remitRatio,

        @Schema(description = "최근 6개월 중 해외송금한 달 수(1~6). 송금하지 않았으면 null", nullable = true, example = "6")
        Integer remitMonths6m,

        @Schema(description = "체류 개월·만료까지 개월·미납 연체 판단 기준일(오늘, KST)", example = "2026-11-16")
        LocalDate asOfDate,

        @Schema(description = "거래 집계 기간 시작. 최근 수집 배치의 기간을 온전한 달로 자르고 끝에서 최대 12개월",
                example = "2025-11-01")
        LocalDate periodFrom,

        @Schema(description = "거래 집계 기간 끝(기준 월의 말일)", example = "2026-10-31")
        LocalDate periodTo,

        @Schema(description = "최근 6개월(연체·송금한 달)의 첫 달 1일. 끝은 periodTo", example = "2026-05-01")
        LocalDate recentFrom
) {
}
