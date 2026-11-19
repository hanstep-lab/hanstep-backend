package com.hanstep.credit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** SOL 글로벌론 상품 상세(CR-002). 모양은 FE Mock(mockGlobalLoan)과 같고 limitEstimate·isMock 이 더 있다. */
@Schema(name = "SolGlobalLoanResponse", description = "SOL 글로벌론 상품 상세와 가입 조건 확인(모의). 실제 대출 승인이 아닙니다")
public record SolGlobalLoanResponse(
        @Schema(example = "신한은행 대출 상품")
        String productTag,

        @Schema(example = "SOL 글로벌론")
        String name,

        @Schema(example = "외국인 전용 직장인 신용대출이에요.")
        String summary,

        Eligibility eligibility,

        Terms terms,

        @Schema(example = "신한걸음 자체 모의 계산이에요. DSR·타행 대출 심사는 반영하지 않았고, 실제 대출 가능 여부와 금리는 "
                + "은행 심사에 따라 달라요.")
        String notice,

        SolGlobalLoanLimitEstimate limitEstimate,

        @Schema(description = "입력이 Mock 마이데이터·임시 소득 기준이라 항상 true", example = "true")
        boolean isMock
) {

    @Schema(name = "SolGlobalLoanEligibility")
    public record Eligibility(
            @Schema(example = "가입 조건 확인")
            String title,

            @Schema(example = "마이데이터·외국인등록증·소득 정보로 확인했어요")
            String source,

            @Schema(description = "충족한 조건 수", example = "5개 중 5개 충족")
            String resultLabel,

            @Schema(description = "가입 조건 5개(salary·visa·nationality·stay·income 순)")
            List<Condition> conditions
    ) {
    }

    @Schema(name = "SolGlobalLoanCondition")
    public record Condition(
            @Schema(description = "salary·visa·nationality·stay·income", example = "visa")
            String key,

            @Schema(example = "대상 체류자격")
            String title,

            @Schema(description = "조건 설명 · 현재 값", example = "E-9·E-7·F-2·F-5 중 하나 · 현재 E-9")
            String desc,

            @Schema(example = "true")
            boolean met
    ) {
    }

    @Schema(name = "SolGlobalLoanTerms")
    public record Terms(
            @Schema(example = "상품 조건")
            String title,

            @Schema(example = "신한은행 상품 안내 기준")
            String basis,

            List<TermRow> rows
    ) {
    }

    @Schema(name = "SolGlobalLoanTermRow")
    public record TermRow(
            @Schema(description = "행 구분(limit·term·repayment·rate). 앱은 label 이 아니라 이 값으로 행을 찾는다",
                    example = "limit")
            String key,

            @Schema(example = "대출 한도")
            String label,

            @Schema(example = "최대 5,000만 원")
            String value
    ) {
    }
}
