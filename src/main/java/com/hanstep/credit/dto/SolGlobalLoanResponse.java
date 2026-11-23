package com.hanstep.credit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** SOL 글로벌론 상품 상세(CR-002). 모양은 FE Mock(mockGlobalLoan)과 같고 limitEstimate·isMock 이 더 있다. */
@Schema(name = "SolGlobalLoanResponse", description = "SOL 글로벌론 상품 상세와 가입 조건 확인(모의). 실제 대출 승인이 아닙니다. "
        + "문구 필드는 preferred_language 가 en 이면 영어(vi·zh 도 영어), ko 면 한국어. key·met·숫자는 언어와 상관없이 같다")
public record SolGlobalLoanResponse(
        @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "신한은행 대출 상품")
        String productTag,

        @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "SOL 글로벌론")
        String name,

        @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "외국인 전용 직장인 신용대출이에요.")
        String summary,

        Eligibility eligibility,

        Terms terms,

        @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "신한걸음 자체 모의 계산이에요. DSR·타행 대출 심사는 반영하지 않았고, 실제 대출 가능 여부와 금리는 "
                + "은행 심사에 따라 달라요.")
        String notice,

        SolGlobalLoanLimitEstimate limitEstimate,

        @Schema(description = "입력이 Mock 마이데이터·임시 소득 기준이라 항상 true", example = "true")
        boolean isMock
) {

    @Schema(name = "SolGlobalLoanEligibility")
    public record Eligibility(
            @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "가입 조건 확인")
            String title,

            @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "마이데이터·외국인등록증·소득 정보로 확인했어요")
            String source,

            @Schema(description = "충족한 조건 수. preferred_language 가 en 이면 영어(vi·zh 도 영어)(예: 5 of 5 met)", example = "5개 중 5개 충족")
            String resultLabel,

            @Schema(description = "가입 조건 5개(salary·visa·nationality·stay·income 순)")
            List<Condition> conditions
    ) {
    }

    @Schema(name = "SolGlobalLoanCondition")
    public record Condition(
            @Schema(description = "salary·visa·nationality·stay·income", example = "visa")
            String key,

            @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "대상 체류자격")
            String title,

            @Schema(description = "조건 설명 · 현재 값. preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "E-9·E-7·F-2·F-5 중 하나 · 현재 E-9")
            String desc,

            @Schema(example = "true")
            boolean met
    ) {
    }

    @Schema(name = "SolGlobalLoanTerms")
    public record Terms(
            @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "상품 조건")
            String title,

            @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "신한은행 상품 안내 기준")
            String basis,

            List<TermRow> rows
    ) {
    }

    @Schema(name = "SolGlobalLoanTermRow")
    public record TermRow(
            @Schema(description = "행 구분(limit·term·repayment·rate). 앱은 label 이 아니라 이 값으로 행을 찾는다",
                    example = "limit")
            String key,

            @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "대출 한도")
            String label,

            @Schema(description = "preferred_language 가 en 이면 영어(vi·zh 도 영어)", example = "최대 5,000만 원")
            String value
    ) {
    }
}
