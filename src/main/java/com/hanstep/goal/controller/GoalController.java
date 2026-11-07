package com.hanstep.goal.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.common.example.DemoExamples;
import com.hanstep.goal.dto.GoalListResponse;
import com.hanstep.goal.dto.GoalSaveRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Goal", description = "금융 목표 (ON-004·005)")
@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

    @Operation(summary = "금융 목표 목록 (GOL-01)",
            description = "ACTIVE 목표만. [계약 단계: 고정 예시 응답]")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public GoalListResponse getGoals(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return DemoExamples.goals();
    }

    @Operation(summary = "금융 목표 일괄 저장 (GOL-02)",
            description = "복수 선택. 전체 교체 방식이라 요청에 없는 기존 목표는 CANCELLED 가 된다. goalType 중복 불가. "
                    + "targetAmount 는 LUMP_SUM(목돈 마련)만 받는다. 저장 후 목록을 돌려준다. [계약 단계: 고정 예시 응답]")
    @ApiResponse(responseCode = "200", description = "저장 성공")
    @ApiResponse(responseCode = "400", description = "형식 오류·goalType 중복(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping
    public GoalListResponse saveGoals(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                                      @Valid @RequestBody GoalSaveRequest request) {
        return DemoExamples.goals();
    }
}
