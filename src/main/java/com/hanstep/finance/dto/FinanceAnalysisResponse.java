package com.hanstep.finance.dto;

import com.hanstep.common.dto.Money;
import com.hanstep.finance.domain.AnalysisStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "금융 분석 결과(FIN-05·06). 금액은 모두 INT-01 계산값, 모의 진단이며 실제 신용평가가 아님. "
        + "꽉 찬 달이 없으면 금액은 모두 null(dataQuality.warnings 에 NO_FULL_MONTH)")
public record FinanceAnalysisResponse(
        @Schema(description = "분석 ID", example = "1")
        Long id,

        @Schema(description = "분석 기간 시작(온전한 달력 월로 자른 값)", example = "2025-11-01")
        LocalDate periodFrom,

        @Schema(description = "분석 기간 끝", example = "2026-10-31")
        LocalDate periodTo,

        @Schema(description = "결과 상태. 응답은 항상 COMPLETED(실패는 502·409 오류로 응답)", example = "COMPLETED")
        AnalysisStatus status,

        @Schema(description = "월평균 소득", nullable = true, example = "{\"amount\": 2350000, \"currency\": \"KRW\"}")
        Money avgMonthlyIncome,

        @Schema(description = "월평균 고정지출(주거·공과금·통신·대출 상환)", nullable = true,
                example = "{\"amount\": 520000, \"currency\": \"KRW\"}")
        Money fixedLiving,

        @Schema(description = "월평균 송금", nullable = true, example = "{\"amount\": 700000, \"currency\": \"KRW\"}")
        Money remittance,

        @Schema(description = "변동지출 월평균·최소·최대", nullable = true)
        VariableExpense variableExpense,

        @Schema(description = "여유자금 = 소득 − 고정 − 송금 − 변동 평균. 음수 가능(지출이 소득보다 많음). "
                + "공통 Money 스키마의 amount 최소 0 은 요청 검증용이라 이 필드에는 해당하지 않음", nullable = true,
                example = "{\"amount\": 675317, \"currency\": \"KRW\"}")
        Money spare,

        DataQuality dataQuality,

        @Schema(description = "집계에서 뺀 거래 수(본인 이체·저축·카드대금 등)", example = "36")
        int excludedCount,

        @Schema(description = "분석에 쓴 적재 배치가 Mock 이면 true", example = "true")
        boolean isMock,

        @Schema(description = "기존 결과 재사용 여부. 지금은 항상 false", example = "false")
        boolean reused,

        @Schema(description = "생성 시각(+09:00)", example = "2026-11-11T14:30:00+09:00")
        OffsetDateTime createdAt
) {

    @Schema(name = "FinanceVariableExpense", description = "변동지출(식비·교통·생활 등) 월 금액")
    public record VariableExpense(
            @Schema(description = "월평균") Money avg,
            @Schema(description = "가장 적게 쓴 달") Money min,
            @Schema(description = "가장 많이 쓴 달") Money max
    ) {
    }

    @Schema(name = "FinanceDataQuality", description = "데이터 품질")
    public record DataQuality(
            @Schema(description = "평균에 쓴 꽉 찬 달 수", example = "12")
            int observedMonthCount,

            @ArraySchema(arraySchema = @Schema(description = "수집이 빠진 달(YYYY-MM)"))
            List<String> missingMonths,

            @ArraySchema(arraySchema = @Schema(description = "일부만 수집된 달(YYYY-MM, 평균에서 뺌)"))
            List<String> partialMonths,

            @ArraySchema(arraySchema = @Schema(description = "경고 코드(NO_FULL_MONTH, UNKNOWN_CATEGORY:코드 등)"))
            List<String> warnings
    ) {
    }
}
