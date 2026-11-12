package com.hanstep.aicontext.dto;

import com.hanstep.commitment.dto.CommitmentTotals;
import com.hanstep.finance.dto.FinanceAnalysisResponse;
import com.hanstep.goal.dto.GoalResponse;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * INT-06 응답. 기존 API 의 DTO 를 그대로 묶는다(새로 계산하지 않음).
 * LLM 에 넘어가는 데이터라 계좌·잔액·계좌번호·거래처·생년월일·등록번호는 넣지 않는다.
 */
@Schema(description = "AI 용 금융 컨텍스트(INT-06)")
public record AiFinanceContextResponse(
        @Schema(description = "사용자 ID", example = "1")
        Long userId,

        @Schema(description = "최신 금융 분석(FIN-06 과 같은 값). 완료된 분석이 없으면 null", nullable = true)
        FinanceAnalysisResponse latestAnalysis,

        @Schema(description = "필수 확보액 합계(CMT-01 totals 와 같은 값). 온보딩에서 사용자가 입력한 값")
        CommitmentTotals commitments,

        @ArraySchema(arraySchema = @Schema(description = "금융 목표(GOL-01 goals 와 같은 값). 없으면 빈 배열"))
        List<GoalResponse> goals
) {
}
