package com.hanstep.fx.controller;

import com.hanstep.common.config.OpenApiConfig;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.common.error.ErrorResponse;
import com.hanstep.common.time.KstDates;
import com.hanstep.fx.config.FxCollectorProperties;
import com.hanstep.fx.domain.FxSource;
import com.hanstep.fx.dto.FxBackfillResponse;
import com.hanstep.fx.service.FxCollectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 관리용. 서버에서 spring 컨테이너 안의 curl 로 부른다(deploy/README.md "환율 수집기"). 외부(Caddy)에서는 /internal 이 막혀 있다.
 */
@Tag(name = "Internal · FX Collector", description = "환율 백필(운영 관리용, X-Internal-Token)")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN)
@RestController
@RequestMapping("/internal/v1/fx")
public class FxCollectorInternalController {

    static final int MAX_CALLS_LIMIT = 1000;

    private final FxCollectService collectService;
    private final FxCollectorProperties properties;

    public FxCollectorInternalController(FxCollectService collectService, FxCollectorProperties properties) {
        this.collectService = collectService;
        this.properties = properties;
    }

    @Operation(summary = "환율 백필",
            description = """
                    from~to(포함) 환율을 원천에서 받아 저장한다. 끝날 때까지 응답하지 않는다(12개월이면 수출입은행 호출 약 260번, \
                    호출 사이 hanstep.fx-collector.backfill.call-interval 만큼 쉰다).
                    Frankfurter(PHP)는 통화마다 1번, 수출입은행(USD 등)은 평일마다 1번 부른다. 수출입은행은 maxCalls 번에서 멈추고 \
                    nextFrom 을 준다(그 날짜를 from 으로 다시 부르면 이어서 한다). 인증키 오류·일일 한도도 거기서 멈춘다.
                    같은 값은 새 행을 만들지 않고 fetched_at 도 고치지 않아 다시 실행해도 된다. 같은 키에 Mock 행이 있으면 건드리지 않고 keptMock 으로 센다.""")
    @ApiResponse(responseCode = "200", description = "실행함(원천별 status·failures 로 성공 여부를 본다)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: from 이 to 보다 늦음, to 가 오늘(KST) 이후, "
            + "maxCalls 가 1~1000 밖, 날짜·source 형식 오류(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "JOB_ALREADY_PROCESSING: 백필이 이미 실행 중",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/backfill")
    public FxBackfillResponse backfill(
            @Parameter(description = "시작일(포함). 기본: to 에서 hanstep.fx-collector.backfill.months(12)개월 전",
                    example = "2025-11-17")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일(포함). 기본: 오늘(KST)", example = "2026-11-17")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "한 원천만(KOREAEXIM 또는 MARKET_REF). 기본: 둘 다")
            @RequestParam(required = false) FxSource source,
            @Parameter(description = "수출입은행 호출 상한(1~1000). 기본 hanstep.fx-collector.backfill.max-calls(400)")
            @RequestParam(required = false) Integer maxCalls) {
        LocalDate today = LocalDate.now(KstDates.KST);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusMonths(properties.backfill().months()) : from;
        int limit = maxCalls == null ? properties.backfill().maxCalls() : maxCalls;
        if (end.isAfter(today)) {
            throw invalid("to", "must not be after today (KST)");
        }
        if (start.isAfter(end)) {
            throw invalid("from", "must not be after to");
        }
        if (limit < 1 || limit > MAX_CALLS_LIMIT) {
            throw invalid("maxCalls", "must be 1~" + MAX_CALLS_LIMIT);
        }
        Set<FxSource> sources = source == null ? EnumSet.allOf(FxSource.class) : EnumSet.of(source);
        return collectService.backfill(start, end, sources, limit);
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
