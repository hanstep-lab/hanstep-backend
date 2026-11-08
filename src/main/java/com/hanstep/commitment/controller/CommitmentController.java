package com.hanstep.commitment.controller;

import com.hanstep.commitment.dto.CommitmentListResponse;
import com.hanstep.commitment.dto.CommitmentSaveRequest;
import com.hanstep.commitment.dto.RemittanceCandidateListResponse;
import com.hanstep.commitment.service.CommitmentService;
import com.hanstep.common.error.ErrorResponse;
import com.hanstep.common.example.DemoExamples;
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

@Tag(name = "Commitment", description = "매달 꼭 나가는 돈 (ON-005)")
@RestController
@RequestMapping("/api/v1/commitments")
public class CommitmentController {

    private final CommitmentService commitmentService;

    public CommitmentController(CommitmentService commitmentService) {
        this.commitmentService = commitmentService;
    }

    @Operation(summary = "필수 확보액 목록 (CMT-01)",
            description = "활성 항목과 유형별 합계(계산값). VARIABLE_EXPENSE(변동지출)는 여유자금 계산 때 빼고, "
                    + "MANDATORY_SAVING(저축)은 빼지 않는다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public CommitmentListResponse getCommitments(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return commitmentService.getCommitments(userId);
    }

    @Operation(summary = "필수 확보액 일괄 저장 (CMT-02)",
            description = "전체 교체 방식. 요청에 없는 기존 항목은 비활성(is_active=false). ON-005 의 고정지출"
                    + "(RENT·UTILITY·TELECOM 등)·송금(REMITTANCE)·변동지출(VARIABLE_EXPENSE)을 한 번에 보낸다. "
                    + "같은 유형 여러 개 가능(예: UTILITY 2건). amount.currency 는 KRW 만. 저장 후 목록·합계를 돌려준다.")
    @ApiResponse(responseCode = "200", description = "저장 성공")
    @ApiResponse(responseCode = "400", description = "형식 오류·KRW 아닌 통화(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "detectedFromTxnId 가 없거나 내 거래가 아님",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping
    public CommitmentListResponse saveCommitments(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                                                  @Valid @RequestBody CommitmentSaveRequest request) {
        return commitmentService.saveCommitments(userId, request);
    }

    @Operation(summary = "송금 이력 Top 3 (CMT-03)",
            description = "'이전 송금 이력을 분석했어요' 블록. Mock 거래내역에서 정기 해외송금을 찾아 월평균 금액 큰 순으로 최대 3개. "
                    + "사용자가 고르면 CMT-02 에 REMITTANCE + DETECTED_CONFIRMED + detectedFromTxnId 로 저장한다. "
                    + "관계(어머니 등)는 거래내역으로 추정하지 않는다. 마이데이터 미연결이면 빈 배열. [계약 단계: 고정 예시 응답]")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/remittance-candidates")
    public RemittanceCandidateListResponse getRemittanceCandidates(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return DemoExamples.remittanceCandidates();
    }
}
