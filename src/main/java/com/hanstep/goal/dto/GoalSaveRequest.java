package com.hanstep.goal.dto;

import com.hanstep.common.dto.Money;
import com.hanstep.goal.domain.GoalType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "금융 목표 일괄 저장(전체 교체). 요청에 없는 기존 목표는 CANCELLED 가 된다")
public record GoalSaveRequest(
        @ArraySchema(arraySchema = @Schema(description = "선택한 목표 전체. goalType 은 중복 불가"))
        @NotNull
        @Size(max = 5)
        List<@Valid @NotNull Item> goals
) {

    @Schema(name = "GoalSaveItem", description = "저장할 목표 하나")
    public record Item(
            @Schema(description = "목표 종류", example = "LUMP_SUM")
            @NotNull
            GoalType goalType,

            @Schema(description = "목표 금액(LUMP_SUM 만). amount 는 0보다 커야 함. 없으면 null",
                    example = "{\"amount\": 6000000, \"currency\": \"KRW\"}")
            @Valid
            Money targetAmount,

            @Schema(description = "목표 시점(YYYY-MM-DD)", example = "2027-10-04", nullable = true)
            @FutureOrPresent
            LocalDate targetDate
    ) {
    }
}
