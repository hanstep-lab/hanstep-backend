package com.hanstep.product.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * PRD-01 금융상품 추천 화면 응답. AI 내부 API(products/recommend, 계약 hanstep-AI ai/specs/products/recommend_api_v1.md)의
 * 응답을 이름·값 그대로 옮기고 isMock 만 더한다. 금리·특징은 받은 값만 옮긴다(더하거나 계산하거나 null 을 채우지 않는다).
 * 화면에서 키가 늘 같도록 null 도 키를 둔다.
 */
@Schema(description = "금융상품 추천 결과(PRD-01). 금리·특징은 상품설명서 원문 값만, 근거가 없으면 null")
public record ProductRecommendResponse(
        @Schema(description = "판정 기준일(Asia/Seoul)", example = "2026-11-17")
        LocalDate asOf,

        @Schema(description = "asOf 부터 체류 만료일까지 채운 개월 수. 만료일이 없으면 null", example = "10",
                nullable = true)
        Integer remainingStayMonths,

        @Schema(description = "순서 출처: search(상품설명서 검색 순) / catalog(검색 기능 없음, 조건표 순) / "
                + "catalog_no_query(검색어 없음, 조건표 순) / catalog_search_failed(검색 실패, 조건표 순). "
                + "어느 경우든 외국인 가입이 원문에 명시된 상품을 먼저 둔다", example = "catalog_no_query")
        String rankedBy,

        @Schema(description = "products 가 비었을 때만 이유 코드: REQUEST_EXCEEDS_SURPLUS(요청 월 납입액이 여유자금보다 큼) / "
                + "STAY_TOO_SHORT(남은 체류보다 짧은 상품이 없음) / NO_MATCHING_PRODUCT(조건에 맞는 상품 없음). 아니면 null",
                example = "null", nullable = true)
        String abstainReason,

        @ArraySchema(arraySchema = @Schema(description = "조건을 통과한 상품 전부(순서는 rankedBy). 기권이면 []"))
        List<Product> products,

        @ArraySchema(arraySchema = @Schema(description = "제외된 상품의 이유별 개수(이름·id 없음). 제외 상품이 없으면 []"))
        List<ExcludedCount> excludedSummary,

        @Schema(description = "여유자금이 마이데이터 Mock 거래로 만든 분석 값이라 항상 true", example = "true")
        boolean isMock
) {

    @Schema(name = "ProductRecommendItem", description = "추천 상품 하나")
    public record Product(
            @Schema(description = "상품 id(상품설명서 문서 id)", example = "doc-a32e7bfdf57e")
            String productId,

            @Schema(description = "상품명", example = "슈퍼SOL 웰컴 정기예금")
            String nameKo,

            @Schema(description = "SAVINGS(적금) / DEPOSIT(정기예금)", example = "DEPOSIT")
            String kind,

            @Schema(description = "계약 기간(개월). 정해진 기간 중 고르는 상품은 정수 배열(예: [6, 12]), "
                    + "범위형은 {min, max}. 근거가 없으면 null", example = "[6, 12]", nullable = true)
            Object termMonths,

            @Schema(description = "적금의 월 납입 범위(원). 예금은 null", nullable = true)
            AmountRange monthlyAmountKrw,

            @Schema(description = "예금의 가입 금액 범위(원). 적금은 null", nullable = true)
            AmountRange depositAmountKrw,

            @Schema(description = "적금의 적립 방식 FLEXIBLE(자유적립) / FIXED(정액적립). 예금은 null", example = "null",
                    nullable = true)
            String accrual,

            @Schema(description = "외국인 가입: explicit(원문 명시) / inferred(해석) / null(근거 없음)",
                    example = "inferred", nullable = true)
            String foreignerEligible,

            @ArraySchema(arraySchema = @Schema(description = "프로필 값이나 근거가 없어 판정하지 못한 조건: "
                    + "visa / age / remainingStay / spareMoney"), schema = @Schema(example = "visa"))
            List<String> notChecked,

            @Schema(description = "가장 짧은 계약 기간이 남은 체류 개월 이하면 true, 판정 불가면 null", example = "true",
                    nullable = true)
            Boolean stayFits,

            @Schema(description = "원문의 기본금리. 근거가 없으면 null(화면은 금리를 표시하지 않음)", nullable = true)
            BaseRate baseRate,

            @Schema(description = "우대 조건 충족 시 최고 금리(원문 값). 화면은 '우대 조건 충족 시' 문구와 함께 표시. "
                    + "원문이 최고(최종) 이자율을 적은 상품에만 있고 없으면 null", nullable = true)
            MaxRate maxRate,

            @Schema(description = "원문 '상품특징' 문장 인용. 근거가 없으면 null", nullable = true)
            Feature feature,

            @ArraySchema(arraySchema = @Schema(description = "판정에 쓴 원문 근거 인용(최대 4개)"))
            List<Evidence> evidence
    ) {
    }

    @Schema(name = "ProductRecommendAmountRange", description = "금액 범위(원)")
    public record AmountRange(
            @Schema(description = "최소(원). 근거가 없으면 null", example = "500000", nullable = true)
            Long min,

            @Schema(description = "최대(원). 원문이 '제한없음'이면 문자열 \"no limit\", 근거가 없으면 null",
                    example = "20000000", nullable = true)
            Object max
    ) {
    }

    @Schema(name = "ProductRecommendBaseRate", description = "기본금리(원문 값)")
    public record BaseRate(
            @Schema(description = "연 이율(%) 원문 값. 기간별로 다르면 가장 짧은 가입 기간의 값", example = "3.3")
            BigDecimal ratePct,

            @Schema(description = "원문의 금리 기준일. 원문에 없으면 null. 화면은 금리와 함께 표시", example = "2026-11-09",
                    nullable = true)
            LocalDate asOf,

            @Schema(description = "금리 원문 인용(기간별이면 나머지 기간 값도 있음)",
                    example = "기본이자율 (2026.11.09 현재, 세전) 6개월 : 연 3.3% 12개월 : 연 3.6%")
            String quote,

            @Schema(description = "인용 쪽", example = "2")
            Integer page
    ) {
    }

    @Schema(name = "ProductRecommendMaxRate",
            description = "우대 조건 충족 시 최고 금리(원문 값). 화면은 '우대 조건 충족 시' 문구와 함께 표시")
    public record MaxRate(
            @Schema(description = "연 이율(%) 원문 값. 기간별이면 가장 짧은 가입 기간의 값", example = "3.25")
            BigDecimal ratePct,

            @Schema(description = "금리 원문 인용", example = "최종이자율 (2026.09.29 현재, 세전) 연 3.25%")
            String quote,

            @Schema(description = "인용 쪽", example = "2")
            Integer page
    ) {
    }

    @Schema(name = "ProductRecommendFeature", description = "상품특징 원문 인용")
    public record Feature(
            @Schema(description = "원문 인용", example = "신한 슈퍼 SOL 신규 고객 및 급여 첫 거래 고객에게 우대금리를 제공하는 정기예금")
            String quote,

            @Schema(description = "인용 쪽", example = "1")
            Integer page
    ) {
    }

    @Schema(name = "ProductRecommendEvidence", description = "판정 근거 인용")
    public record Evidence(
            @Schema(description = "근거 항목: term / amount / accrual / foreigners", example = "term")
            String about,

            @Schema(description = "원문 인용", example = "계약기간 6개월, 12개월")
            String quote,

            @Schema(description = "인용 쪽", example = "1")
            Integer page
    ) {
    }

    @Schema(name = "ProductRecommendExcludedCount", description = "제외 이유별 상품 수")
    public record ExcludedCount(
            @Schema(description = "제외 이유(추천기 판정 이름): recommendable / kind / foreigner / visa / age / stayTerm / "
                    + "spare / requestTerm / requestMonthly / savingType / requireForeignerExplicit", example = "stayTerm")
            String reason,

            @Schema(description = "상품 수", example = "5")
            Integer count
    ) {
    }
}
