package com.hanstep.consent.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.common.example.DemoExamples;
import com.hanstep.consent.dto.ConsentListResponse;
import com.hanstep.consent.dto.ConsentSaveRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Consent", description = "약관·마이데이터 동의 (ON-003A)")
@RestController
@RequestMapping("/api/v1/consents")
public class ConsentController {

    @Operation(summary = "동의 현황 조회 (CNS-01)",
            description = "동의 종류별 최신 1건. 한 번도 동의하지 않은 종류는 목록에 없다. [계약 단계: 고정 예시 응답]")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public ConsentListResponse getConsents(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return DemoExamples.consents();
    }

    @Operation(summary = "동의 일괄 저장 (CNS-02)",
            description = "ON-003A 마이데이터 연결 동의에서는 MYDATA·MYDATA_TERMS 두 건을 모두 granted=true 로 보낸다"
                    + "(둘 중 하나라도 없으면 MYD-02 가 403 CONSENT_REQUIRED). granted=false 는 철회(REVOKED)로 이력에 남긴다. "
                    + "저장한 종류의 최신 상태를 돌려준다. [계약 단계: 고정 예시 응답]")
    @ApiResponse(responseCode = "201", description = "저장 성공")
    @ApiResponse(responseCode = "200", description = "같은 Idempotency-Key 재요청: 처음 결과를 그대로 반환")
    @ApiResponse(responseCode = "400", description = "형식 오류(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentListResponse saveConsents(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "중복 저장 방지 키(선택, UUID 권장)", example = "3f1c9b2e-7a41-4c0e-9d1a-2b6f0c8e5a17")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConsentSaveRequest request) {
        return DemoExamples.savedMydataConsents();
    }
}
