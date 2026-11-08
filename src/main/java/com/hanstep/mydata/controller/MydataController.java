package com.hanstep.mydata.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.common.example.DemoExamples;
import com.hanstep.mydata.dto.ConnectionCreateRequest;
import com.hanstep.mydata.dto.ConnectionListResponse;
import com.hanstep.mydata.dto.InstitutionListResponse;
import com.hanstep.mydata.service.MydataConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마이데이터(Mock). 실연동은 금융위 허가가 필요해 Mock 기관·데이터만 쓴다. 응답의 isMock=true.
 */
@Tag(name = "MyData", description = "마이데이터 기관 연결 (ON-003A·B, Mock)")
@RestController
@RequestMapping("/api/v1/mydata")
public class MydataController {

    private final MydataConnectionService connectionService;

    public MydataController(MydataConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @Operation(summary = "연결 가능 기관 목록 (MYD-01)",
            description = "MVP: 신한은행·신한카드(Mock). [계약 단계: 고정 예시 응답]")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/institutions")
    public InstitutionListResponse getInstitutions(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return DemoExamples.institutions();
    }

    @Operation(summary = "기관 연결 + Mock 데이터 적재 (MYD-02)",
            description = "ON-003B 모달 '확인하고 연결'. 고른 기관을 연결하고 Mock 계좌·거래(최근 lookbackMonths 개월)를 적재한 뒤 "
                    + "연결 상태 전체를 돌려준다(MYD-03 과 같은 형식). Idempotency-Key 필수. "
                    + "MYDATA·MYDATA_TERMS 동의(CNS-02)가 없으면 403.")
    @ApiResponse(responseCode = "201", description = "연결·적재 성공")
    @ApiResponse(responseCode = "200", description = "같은 Idempotency-Key 재요청: 이미 처리된 요청이면 현재 연결 상태를 반환")
    @ApiResponse(responseCode = "400", description = "Idempotency-Key 누락 또는 형식 오류(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "CONSENT_REQUIRED: MYDATA·MYDATA_TERMS 동의 없음(details.consentType)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없는 기관 ID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "같은 Idempotency-Key 요청이 처리 중이면 DUPLICATE_REQUEST, "
            + "실패했으면 INVALID_STATE(새 키로 다시 요청)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/connections")
    public ResponseEntity<ConnectionListResponse> connect(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "중복 연결 방지 키(필수, UUID 권장)", required = true,
                    example = "8d0b6a5e-2f7c-4e19-b3a4-6c1e9f02d7b8")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ConnectionCreateRequest request) {
        MydataConnectionService.ConnectResult result = connectionService.connect(userId, idempotencyKey, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @Operation(summary = "마이데이터 연결 상태 (MYD-03)",
            description = "기관별 연동 상태와 가져온 계좌·거래 수. 연결한 적 없으면 connected=false, connections=[]. "
                    + "[계약 단계: 고정 예시 응답]")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/connections")
    public ConnectionListResponse getConnections(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return DemoExamples.connections();
    }
}
