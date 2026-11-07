package com.hanstep.onboarding.controller;

import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.error.ErrorResponse;
import com.hanstep.common.example.DemoExamples;
import com.hanstep.onboarding.domain.OnboardingStep;
import com.hanstep.onboarding.dto.OnboardingProfileRequest;
import com.hanstep.onboarding.dto.OnboardingProfileResponse;
import com.hanstep.onboarding.dto.OnboardingResponse;
import com.hanstep.onboarding.dto.OnboardingSummaryResponse;
import com.hanstep.onboarding.dto.StepUpdateRequest;
import com.hanstep.onboarding.dto.StepUpdateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 온보딩 ON-001 ~ ON-006. 지금은 API 계약(Swagger) 단계라 고정 예시 응답을 돌려준다(저장하지 않음).
 */
@Tag(name = "Onboarding", description = "온보딩 진행·프로필·최종 확인 (ON-001 ~ ON-006)")
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    @Operation(summary = "온보딩 진행 상태 (ONB-01)",
            description = "상단 진행 바·이어하기용. 6단계를 항상 화면 순서대로 반환하고 기록 없는 단계는 NOT_STARTED. "
                    + "profile 은 ON-001 설문 응답. [계약 단계: 고정 예시 응답]")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public OnboardingResponse getOnboarding(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return DemoExamples.onboarding();
    }

    @Operation(summary = "온보딩 프로필 저장 (ONB-02)",
            description = "거주 목적·월소득·입국일·거주지역·숙소 제공 여부. 1인 1행 UPSERT(보낸 값으로 덮어씀). "
                    + "저장된 프로필을 돌려준다. [계약 단계: 고정 예시 응답]")
    @ApiResponse(responseCode = "200", description = "저장 성공")
    @ApiResponse(responseCode = "400", description = "형식 오류(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping("/profile")
    public OnboardingProfileResponse saveProfile(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                                                 @Valid @RequestBody OnboardingProfileRequest request) {
        return DemoExamples.profile();
    }

    @Operation(summary = "온보딩 단계 완료·건너뛰기 (ONB-03)",
            description = "각 화면 '다음' 버튼. ON-003A '나중에 연결할게요' = MYDATA_CONSENT 를 SKIPPED. "
                    + "같은 단계를 다시 보내면 덮어쓴다. 응답의 currentStep 으로 다음 화면을 정한다. "
                    + "[계약 단계: 요청 값을 그대로 돌려주고 저장하지 않음]")
    @ApiResponse(responseCode = "200", description = "저장 성공")
    @ApiResponse(responseCode = "400", description = "없는 stepCode, 또는 status 가 COMPLETED·SKIPPED 가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/steps/{stepCode}")
    public StepUpdateResponse updateStep(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "단계 코드", example = "MYDATA_CONSENT") @PathVariable OnboardingStep stepCode,
            @Valid @RequestBody StepUpdateRequest request) {
        if (!request.status().isDone()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, null, Map.of("fields",
                    List.of(Map.of("field", "status", "reason", "must be COMPLETED or SKIPPED"))));
        }
        // 예시: 앞 단계는 모두 끝났다고 보고 바로 다음 단계를 currentStep 으로 준다
        OnboardingStep[] steps = OnboardingStep.values();
        boolean last = stepCode.ordinal() == steps.length - 1;
        OnboardingStep next = last ? null : steps[stepCode.ordinal() + 1];
        return new StepUpdateResponse(stepCode, request.status(), DemoExamples.nowKst(), last, next);
    }

    @Operation(summary = "온보딩 최종 확인 데이터 (ONB-04)",
            description = "ON-006 화면 전체를 한 번에. 잔여일(daysRemaining)·합계·여유자금(spare)은 계산값이고 "
                    + "spare = avgMonthlyIncome − fixedLiving − remittance − variableExpense (MANDATORY_SAVING 은 빼지 않음). "
                    + "등록증을 확정하지 않았으면 visa 는 null. [계약 단계: 고정 예시 응답, daysRemaining 만 오늘 기준 계산]")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/summary")
    public OnboardingSummaryResponse getSummary(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return DemoExamples.summary();
    }
}
