package com.hanstep.fx.controller;

import com.hanstep.common.config.OpenApiConfig;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.error.ErrorResponse;
import com.hanstep.fx.dto.FxAlertEvaluateResponse;
import com.hanstep.fx.service.FxAlertTriggerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시연·운영 관리용. 서버에서 spring 컨테이너 안의 curl 로 부른다(deploy/README.md "목표 환율 알림 다시 판정").
 * 외부(Caddy)에서는 /internal 이 막혀 있다.
 */
@Tag(name = "Internal · FX Alert", description = "목표 환율 알림 다시 판정(시연·관리용, X-Internal-Token)")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN)
@RestController
@RequestMapping("/internal/v1/fx/alerts")
public class FxAlertInternalController {

    private final FxAlertTriggerService triggerService;

    public FxAlertInternalController(FxAlertTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @Operation(summary = "목표 환율 알림 다시 판정",
            description = """
                    현재 최신 실데이터 값(통화마다 우선 원천 KOREAEXIM > MARKET_REF, Mock 제외)으로 ACTIVE 알림을 판정한다. \
                    규칙은 새 환율 저장 때와 같다(SEND 는 send_rate·BASE 는 base_rate, 값이 없으면 판정 안 함, LTE 이하·GTE 이상, \
                    조건부 UPDATE).
                    기본은 알림 created_at 이후 rate_at 만 보므로, 알림보다 먼저 있던 최신 값으로는 바뀌지 않는다(RATE_BEFORE_CREATED). \
                    시연에서 지금 값으로 도달 장면을 보여 주려면 ignoreCreatedAt=true 로 부른다.""")
    @ApiResponse(responseCode = "200", description = "판정함(통화·알림별 결과)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: currency 가 ISO 4217 세 글자가 아님(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "FX_RATE_NOT_FOUND: currency 를 줬는데 그 통화의 실데이터 환율이 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/evaluate")
    public FxAlertEvaluateResponse evaluate(
            @Parameter(description = "한 통화만. 기본: ACTIVE 알림이 있는 통화 전부", example = "PHP")
            @RequestParam(required = false) String currency,
            @Parameter(description = "true 면 created_at 규칙을 빼고 판정(시연용). 기본 false")
            @RequestParam(defaultValue = "false") boolean ignoreCreatedAt) {
        if (currency != null && !currency.matches("[A-Z]{3}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    Map.of("fields", List.of(Map.of("field", "currency", "reason", "must be ISO 4217 (3 letters)"))));
        }
        return triggerService.evaluateLatest(currency, ignoreCreatedAt);
    }
}
