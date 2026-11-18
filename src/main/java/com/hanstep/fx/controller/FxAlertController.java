package com.hanstep.fx.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.fx.dto.FxAlertCreateRequest;
import com.hanstep.fx.dto.FxAlertListResponse;
import com.hanstep.fx.dto.FxAlertNotificationListResponse;
import com.hanstep.fx.dto.FxAlertResponse;
import com.hanstep.fx.service.FxAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "FX Alert", description = "목표 환율 알림 (FX-002). 원격 푸시·자동 송금은 없다. 앱이 켜져 있는 동안 "
        + "스트림(GET /stream)으로 도달을 받아 로컬 알림을 띄우고 POST /{alertId}/notified 를 보낸다")
@RestController
@RequestMapping("/api/v1/fx/alerts")
public class FxAlertController {

    // 응답 예시. 환율 값은 형식을 보여 주는 값이다
    static final String CREATED_EXAMPLE = """
            {"alertId": 3, "currency": "PHP", "rateType": "SEND", "comparator": "LTE", "targetRate": 22.1,
             "unitAmount": 1, "status": "ACTIVE", "createdAt": "2026-11-17T14:30:00+09:00",
             "triggeredAt": null, "triggeredRate": null, "notifiedAt": null}""";
    static final String LIST_EXAMPLE = """
            {"alerts": [
              {"alertId": 3, "currency": "PHP", "rateType": "SEND", "comparator": "LTE", "targetRate": 22.1,
               "unitAmount": 1, "status": "ACTIVE", "createdAt": "2026-11-17T14:30:00+09:00",
               "triggeredAt": null, "triggeredRate": null, "notifiedAt": null},
              {"alertId": 1, "currency": "VND", "rateType": "BASE", "comparator": "GTE", "targetRate": 5.4,
               "unitAmount": 100, "status": "TRIGGERED", "createdAt": "2026-11-10T09:00:00+09:00",
               "triggeredAt": "2026-11-12T16:02:00+09:00", "triggeredRate": 5.4296,
               "notifiedAt": "2026-11-12T16:02:01+09:00"}
            ]}""";
    static final String EMPTY_LIST_EXAMPLE = """
            {"alerts": []}""";
    static final String VALIDATION_EXAMPLE = """
            {"code": "VALIDATION_ERROR", "message": "invalid request",
             "details": {"fields": [
               {"field": "currency", "reason": "must be 3 uppercase letters (ISO 4217)"},
               {"field": "targetRate", "reason": "must have at most 14 integer digits and 4 decimal places"}]},
             "requestId": "req_3f9a1c2b7d4e"}""";
    static final String SEND_NOT_AVAILABLE_EXAMPLE = """
            {"code": "VALIDATION_ERROR", "message": "invalid request",
             "details": {"reason": "SEND_RATE_NOT_AVAILABLE",
                         "fields": [{"field": "rateType", "reason": "VND has no send rate (MARKET_REF only), use BASE"}]},
             "requestId": "req_3f9a1c2b7d4e"}""";
    static final String MALFORMED_EXAMPLE = """
            {"code": "VALIDATION_ERROR", "message": "malformed request body", "details": {},
             "requestId": "req_3f9a1c2b7d4e"}""";
    static final String UNAUTHORIZED_EXAMPLE = """
            {"code": "UNAUTHORIZED", "message": "authentication required", "details": {},
             "requestId": "req_3f9a1c2b7d4e"}""";
    static final String RATE_NOT_FOUND_EXAMPLE = """
            {"code": "FX_RATE_NOT_FOUND", "message": "exchange rate not found", "details": {},
             "requestId": "req_3f9a1c2b7d4e"}""";
    static final String DUPLICATE_EXAMPLE = """
            {"code": "DUPLICATE_REQUEST", "message": "duplicate request", "details": {"alertId": 3},
             "requestId": "req_3f9a1c2b7d4e"}""";
    static final String ALERT_ID_INVALID_EXAMPLE = """
            {"code": "VALIDATION_ERROR", "message": "invalid request",
             "details": {"fields": [{"field": "alertId", "reason": "invalid value"}]},
             "requestId": "req_3f9a1c2b7d4e"}""";
    static final String NOT_FOUND_EXAMPLE = """
            {"code": "RESOURCE_NOT_FOUND", "message": "resource not found", "details": {},
             "requestId": "req_3f9a1c2b7d4e"}""";
    static final String NOTIFICATIONS_EXAMPLE = """
            {"notifications": [
              {"alertId": 12, "currency": "PHP", "rateType": "BASE", "comparator": "GTE", "targetRate": 23.5,
               "unitAmount": 1, "triggeredRate": 23.6154, "triggeredAt": "2026-11-18T15:02:11+09:00",
               "rateDate": "2026-11-17", "source": "MARKET_REF"},
              {"alertId": 9, "currency": "USD", "rateType": "SEND", "comparator": "LTE", "targetRate": 1350,
               "unitAmount": 1, "triggeredRate": 1344.2, "triggeredAt": "2026-11-18T11:05:40+09:00",
               "rateDate": "2026-11-18", "source": "KOREAEXIM"}
            ]}""";
    static final String EMPTY_NOTIFICATIONS_EXAMPLE = """
            {"notifications": []}""";
    static final String INVALID_STATE_EXAMPLE = """
            {"code": "INVALID_STATE", "message": "invalid state", "details": {"status": "ACTIVE"},
             "requestId": "req_3f9a1c2b7d4e"}""";

    private final FxAlertService fxAlertService;

    public FxAlertController(FxAlertService fxAlertService) {
        this.fxAlertService = fxAlertService;
    }

    @Operation(summary = "목표 환율 알림 만들기 (FX-002)", description = """
            그 통화의 rateType 환율이 targetRate 이하(LTE)·이상(GTE)이 되는지 볼 알림을 만든다. 자동 송금은 없다.
            targetRate 는 고시 단위(unitAmount)당 원화다(VND 는 100 VND 당).
            같은 통화·같은 comparator 의 ACTIVE 알림은 1개만 둘 수 있다(rateType·targetRate 가 달라도 409). \
            방향이 다르면(LTE 와 GTE) 따로 만들 수 있고, 지우거나(CANCELLED) 도달한(TRIGGERED) 알림은 막지 않는다.
            새 환율(실데이터)이 저장되면 도달 판정을 한다. 알림을 만든 뒤 처음 저장된 환율만 보므로, 이미 있던 값으로 바로 바뀌지 않는다.""")
    @ApiResponse(responseCode = "201", description = "만듦", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = FxAlertResponse.class),
            examples = @ExampleObject(name = "성공", value = CREATED_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR. 형식 오류(details.fields): currency 가 대문자 3자리 "
            + "아님, comparator·targetRate 누락, targetRate 0 이하·소수 5자리 이상·정수부 15자리 이상. "
            + "comparator·rateType 이 정해진 값이 아니거나 JSON 이 깨지면 details 없이 malformed request body. "
            + "송금 보낼 때 환율이 없는 통화에 SEND(생략 포함)면 details.reason=SEND_RATE_NOT_AVAILABLE",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = {@ExampleObject(name = "형식 오류", value = VALIDATION_EXAMPLE),
                            @ExampleObject(name = "송금 환율 없음", description = "VND 처럼 시장 환율 참고값(MARKET_REF)만 "
                                    + "있는 통화는 rateType=BASE 로 다시 보낸다", value = SEND_NOT_AVAILABLE_EXAMPLE),
                            @ExampleObject(name = "본문 형식 오류", value = MALFORMED_EXAMPLE)}))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
            examples = @ExampleObject(name = "토큰 없음", value = UNAUTHORIZED_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "FX_RATE_NOT_FOUND: 환율이 저장되지 않은 통화(예: KRW, 수집 전 통화)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "환율 없는 통화", value = RATE_NOT_FOUND_EXAMPLE)))
    @ApiResponse(responseCode = "409", description = "DUPLICATE_REQUEST: 같은 통화·comparator 의 ACTIVE 알림이 이미 있음"
            + "(details.alertId 가 기존 알림). 동시에 두 번 보내도 하나만 만들어진다",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "이미 있음", value = DUPLICATE_EXAMPLE)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FxAlertResponse create(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                                  @Valid @RequestBody FxAlertCreateRequest request) {
        return fxAlertService.create(userId, request);
    }

    @Operation(summary = "내 목표 환율 알림 목록 (FX-002)",
            description = "ACTIVE·TRIGGERED 만(지운 CANCELLED 는 빠짐). 만든 시각 최신순, 같으면 alertId 큰 순. "
                    + "TRIGGERED 는 triggeredAt·triggeredRate 가 함께 오고, 앱이 알렸음을 보냈으면 notifiedAt 도 온다")
    @ApiResponse(responseCode = "200", description = "성공", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = FxAlertListResponse.class),
            examples = {@ExampleObject(name = "성공", value = LIST_EXAMPLE),
                    @ExampleObject(name = "알림 없음", value = EMPTY_LIST_EXAMPLE)}))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
            examples = @ExampleObject(name = "토큰 없음", value = UNAUTHORIZED_EXAMPLE)))
    @GetMapping
    public FxAlertListResponse list(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return fxAlertService.list(userId);
    }

    @Operation(summary = "목표 환율 알림 지우기 (FX-002)",
            description = "행은 지우지 않고 CANCELLED 로 바꿔 목록에서 뺀다. 이미 지운 알림을 다시 지워도 204. "
                    + "지운 뒤에는 같은 통화·comparator 로 새로 만들 수 있다")
    @ApiResponse(responseCode = "204", description = "지움(본문 없음). 이미 지운 알림 포함")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: alertId 가 숫자가 아님(details.fields)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "alertId 형식 오류", value = ALERT_ID_INVALID_EXAMPLE)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
            examples = @ExampleObject(name = "토큰 없음", value = UNAUTHORIZED_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 없는 알림이거나 남의 알림(있는지 알려 주지 않음)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "없음", value = NOT_FOUND_EXAMPLE)))
    @DeleteMapping("/{alertId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                       @Parameter(description = "알림 ID", example = "3") @PathVariable Long alertId) {
        fxAlertService.delete(userId, alertId);
    }

    @Operation(summary = "아직 알리지 않은 목표 환율 도달 (FX-002)", description = """
            내 알림 중 TRIGGERED 이고 notifiedAt 이 없는 것. 도달 시각 최신순(같으면 alertId 큰 순). 지운 알림(CANCELLED)은 빠진다.
            스트림(GET /stream)이 연결될 때 먼저 보내는 보충과 같은 항목·순서다. 스트림을 쓰지 못할 때 앱 시작 시 한 번 확인하는 용도.
            알림 문장은 서버가 만들지 않는다(앱이 화면 언어로 만든다).""")
    @ApiResponse(responseCode = "200", description = "성공", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = FxAlertNotificationListResponse.class),
            examples = {@ExampleObject(name = "성공", value = NOTIFICATIONS_EXAMPLE),
                    @ExampleObject(name = "없음", value = EMPTY_NOTIFICATIONS_EXAMPLE)}))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
            examples = @ExampleObject(name = "토큰 없음", value = UNAUTHORIZED_EXAMPLE)))
    @GetMapping("/notifications")
    public FxAlertNotificationListResponse notifications(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return fxAlertService.notifications(userId);
    }

    @Operation(summary = "목표 환율 도달 알렸음 (FX-002)", description = """
            앱이 도달을 받아 로컬 알림을 띄운 뒤 보낸다. notifiedAt 을 채우고, 그 뒤로는 보충(notifications·스트림 연결 시)에 나오지 않는다.
            서버는 스트림으로 보낸 것만으로 알렸다고 보지 않는다. 이걸 보내지 않으면 다음 연결 때 같은 alertId 가 다시 온다.
            이미 알렸으면 그대로 204(처음 시각 유지).""")
    @ApiResponse(responseCode = "204", description = "알렸음 기록(본문 없음). 이미 알린 알림 포함")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: alertId 가 숫자가 아님(details.fields)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "alertId 형식 오류", value = ALERT_ID_INVALID_EXAMPLE)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class),
            examples = @ExampleObject(name = "토큰 없음", value = UNAUTHORIZED_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 없는 알림이거나 남의 알림(있는지 알려 주지 않음)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "없음", value = NOT_FOUND_EXAMPLE)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: TRIGGERED 가 아님(details.status 가 지금 상태). "
            + "ACTIVE 는 아직 도달 전, CANCELLED 는 그사이 지운 알림이라 앱은 알리지 않고 넘어가면 된다",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(name = "도달 전", value = INVALID_STATE_EXAMPLE)))
    @PostMapping("/{alertId}/notified")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void notified(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                         @Parameter(description = "알림 ID", example = "12") @PathVariable Long alertId) {
        fxAlertService.markNotified(userId, alertId);
    }
}
