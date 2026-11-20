package com.hanstep.chat.controller;

import com.hanstep.chat.dto.ChatMessageListResponse;
import com.hanstep.chat.dto.ChatSessionCreateRequest;
import com.hanstep.chat.dto.ChatSessionListResponse;
import com.hanstep.chat.dto.ChatSessionResponse;
import com.hanstep.chat.dto.ChatStreamRequest;
import com.hanstep.chat.service.ChatSessionService;
import com.hanstep.chat.service.ChatStreamService;
import com.hanstep.common.error.ErrorResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Chat", description = "AI 챗봇 대화방·메시지")
@RestController
@RequestMapping("/api/v1/chat/sessions")
public class ChatController {

    static final String SESSION_EXAMPLE = """
            {"id": 12, "title": null, "status": "ACTIVE", "lastMessageAt": null,
             "createdAt": "2026-11-17T14:30:00+09:00"}""";
    // 카드 값은 실제 출력이다(#141). 송금: 운영 대화방 1번에 저장된 값. 환율: AI 계약 문서
    // fx_summary_tool_v1.md 의 agent.done 샘플. E-7-4: AI fixture e74_assessment_tool_response.json 으로 만든 카드.
    // 상품 추천: AI 계약 문서 product_recommend_card_v1.md(hanstep-AI bfee51b) 예시 1, 추천기 실행 출력(#151).
    // 끝의 \ 는 줄을 잇는다(SSE data 는 한 줄이어야 한다).
    static final String REMITTANCE_CARD = """
            {"type":"REMITTANCE_CONFIRM","recipient":{"recipientId":1,"nameEn":"LORNA REYES","relationship":"MOTHER",\
            "bankName":"BDO","accountLast4":"5566","countryCode":"PH","currency":"PHP"},\
            "sendAmount":{"amount":500000,"currency":"KRW"},"receiveAmount":{"amount":23521,"currency":"PHP"},\
            "appliedRate":{"currency":"PHP","unit":1,"rate":20.984,"asOf":"2026-11-17",\
            "sourceLabel":"ECB 기준환율 기반 참고값","isMock":false},\
            "transferPath":"/transfer?recipientId=1&amount=500000"}""";
    static final String FX_SUMMARY_CARD = """
            {"type":"FX_SUMMARY_CARD","currencies":[\
            {"currency":"USD","unit":1,"rate":1406.51,"sendRate":1420.5751,"receiveRate":1392.4449,\
            "change":-1.08,"changeRate":-0.08,"source":"KOREAEXIM","asOf":"2026-11-06",\
            "fetchedAt":"2026-11-06T11:05:00+09:00","periodAverages":[\
            {"period":"1M","avg":1407.7735,"diff":-1.2635,"diffRate":-0.09,"direction":"SAME","businessDays":23},\
            {"period":"3M","avg":1429.6566,"diff":-23.1466,"diffRate":-1.62,"direction":"LOWER","businessDays":62},\
            {"period":"6M","avg":1419.9966,"diff":-13.4866,"diffRate":-0.95,"direction":"LOWER","businessDays":126},\
            {"period":"1Y","avg":1418.8393,"diff":-12.3293,"diffRate":-0.87,"direction":"LOWER","businessDays":246}],\
            "isMock":true,"stale":true},\
            {"currency":"PHP","unit":1,"rate":20.984,"sendRate":null,"receiveRate":null,\
            "change":0.112,"changeRate":0.54,"source":"MARKET_REF","asOf":"2026-11-17",\
            "fetchedAt":"2026-11-18T14:13:52+09:00","periodAverages":[\
            {"period":"1M","avg":21.0393,"diff":-0.0553,"diffRate":-0.26,"direction":"LOWER","businessDays":22},\
            {"period":"3M","avg":21.5431,"diff":-0.5591,"diffRate":-2.6,"direction":"LOWER","businessDays":67},\
            {"period":"6M","avg":22.9364,"diff":-1.9524,"diffRate":-8.51,"direction":"LOWER","businessDays":132},\
            {"period":"1Y","avg":23.8157,"diff":-2.8317,"diffRate":-11.89,"direction":"LOWER","businessDays":255}],\
            "isMock":false,"stale":false}],"unavailableCurrencies":[]}""";
    static final String E74_SCORE_CARD = """
            {"type":"E74_SCORE_CARD","assessmentId":77,"status":"ELIGIBLE","total":305,"potentialTotal":305,\
            "required":200,"ruleVersion":"E74-2026-v1","ruleStatus":"DRAFT","isScenario":false}""";
    static final String PRODUCT_RECOMMEND_CARD = """
            {"type":"PRODUCT_RECOMMEND_CARD","asOf":"2026-11-20","remainingStayMonths":10,"abstainReason":null,\
            "products":[{"productId":"doc-de733331abd3","nameKo":"신한 땡겨요 적금","kind":"SAVINGS","termMonths":{"min":6,\
            "max":6},"monthlyAmountKrw":{"min":1000,"max":300000},"depositAmountKrw":null,"accrual":"FLEXIBLE",\
            "foreignerEligible":"inferred","notChecked":["visa","age"],"stayFits":true,"baseRate":{"ratePct":2.2,\
            "asOf":"2026-10-01","quote":"기본이자율 (2026.10.01 현재, 세전) 연 2.20%","page":1},"maxRate":{"ratePct":3.7,\
            "quote":"최고이자율 (2026.10.01 현재, 세전) 최고 연 3.70%","page":2},\
            "feature":{"quote":"신한은행 배달앱 “땡겨요” 서비스를 이용하거나 주단위 적금 납입 미션 달성 고객에게 우대금리를 제공하는 적립식 상품","page":1},\
            "evidence":[{"about":"term","quote":"계약기간 6개월","page":1},{"about":"amount","quote":"최소 1천원 이상 월 30만원 이하",\
            "page":1},{"about":"accrual","quote":"상품유형 자유적립식","page":1},{"about":"foreigners",\
            "quote":"가입대상 실명의 개인 및 개인사업자","page":1}]},{"productId":"doc-ad95d3f72e39","nameKo":"정기적금",\
            "kind":"SAVINGS","termMonths":{"min":1,"max":60},"monthlyAmountKrw":{"min":10000,"max":"no limit"},\
            "depositAmountKrw":null,"accrual":"FIXED","foreignerEligible":"inferred","notChecked":["visa","age"],\
            "stayFits":true,"baseRate":{"ratePct":2.2,"asOf":"2026-09-03",\
            "quote":"기본이자율 (2026.09.03 현재, 세전) 가입기간 1개월이상 3개월미만 3개월이상 6개월미만 6개월이상 12개월미만 12개월이상 24개월미만 정기적립식 연 2.20% 연 2.25% 연 2.30% 연 2.45% 가입기간 24개월이상 36개월미만 36개월 37개월이상 60개월이하 정기적립식 연 2.55% 연 2.55% 연 2.70%",\
            "page":1},"maxRate":null,"feature":{"quote":"일정 기간 후에 일정 금액을 지급할 것을 미리 약정하고 월 1회 일정한 일자에 일정금액을 수입하는 예금",\
            "page":1},"evidence":[{"about":"term","quote":"1개월 이상 60개월 이하(월단위)","page":3},{"about":"amount",\
            "quote":"최저가입금액 1만원 이상","page":5},{"about":"accrual","quote":"상품유형 정기적립식","page":3},\
            {"about":"foreigners","quote":"가입대상 제한없음","page":5}]},{"productId":"doc-a5851b226169",\
            "nameKo":"한 달부터 적금","kind":"SAVINGS","termMonths":{"min":1,"max":12},"monthlyAmountKrw":null,\
            "depositAmountKrw":null,"accrual":"FLEXIBLE","foreignerEligible":"inferred","notChecked":["visa","age",\
            "spareMoney"],"stayFits":true,"baseRate":{"ratePct":1.8,"asOf":"2026-09-03",\
            "quote":"기본이자율 (2026.09.03 현재, 세전) 연 1.80%","page":2},"maxRate":{"ratePct":3.8,\
            "quote":"최고이자율 (2026.09.03 현재, 세전) 연 3.80%","page":2},\
            "feature":{"quote":"1 개월 이상 12 개월 이내 가입 가능한 상품으로, 납입 주기에 따른 총 납입 회차에 대해 일정 회차 이상 납입 시 우대이자율을 제공하는 적립식 예금",\
            "page":1},"evidence":[{"about":"term","quote":"계약기간 1개월 이상 12개월 이하","page":1},{"about":"accrual",\
            "quote":"상품유형 자유적립식","page":1},{"about":"foreigners","quote":"가입대상 실명의 개인 및 개인사업자","page":1}]}],\
            "excludedSummary":[{"reason":"recommendable","count":1},{"reason":"kind","count":4},{"reason":"stayTerm",\
            "count":5}]}""";

    static final String MESSAGES_EXAMPLE = """
            {"messages": [
              {"id": 10, "role": "USER", "content": "Send 500,000 KRW to my mother.", "blocks": null,
               "status": "COMPLETED", "clientMessageId": "c0a8012e-7f3b-4d1a-9c55-2b6f0e3d9a10",
               "runId": "run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59", "replyToId": null, "sources": [],
               "latencyMs": null, "createdAt": "2026-11-17T14:30:00+09:00"},
              {"id": 11, "role": "ASSISTANT",
               "content": "Sending 500,000 KRW to LORNA REYES comes to 23,521 PHP (1 PHP = 20.984 KRW).",
               "blocks": [""" + REMITTANCE_CARD + """
            ], "status": "COMPLETED", "clientMessageId": null,
               "runId": "run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59", "replyToId": 10, "sources": [],
               "latencyMs": 4210, "createdAt": "2026-11-17T14:30:04+09:00"}
            ],
             "page": {"nextCursor": null, "hasNext": false}}""";
    // 송금·환율 도구는 resultRef·근거를 만들지 않는다(resultRef null, chat.sources 없음).
    // 도구 실행 시간(latencyMs)은 출처가 없어 null 로 둔다.
    static final String STREAM_REMITTANCE_EXAMPLE = """
            event: chat.started
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","userMessageId":10,"assistantMessageId":11}

            event: chat.tool_call
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","order":1,"toolName":"quote_remittance","status":"SUCCEEDED","resultRef":null,"latencyMs":null}

            event: chat.delta
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","text":"Sending 500,000 KRW to LORNA REYES "}

            event: chat.delta
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","text":"comes to 23,521 PHP (1 PHP = 20.984 KRW)."}

            event: chat.done
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","assistantMessageId":11,"blocks":[""" + REMITTANCE_CARD + """
            ],"latencyMs":4210}
            """;
    static final String STREAM_FX_EXAMPLE = """
            event: chat.started
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","userMessageId":10,"assistantMessageId":11}

            event: chat.tool_call
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","order":1,"toolName":"fx_rate_summary","status":"SUCCEEDED","resultRef":null,"latencyMs":null}

            event: chat.delta
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","text":"1 USD = 1406.51 KRW "}

            event: chat.delta
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","text":"and 1 PHP = 20.984 KRW."}

            event: chat.done
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","assistantMessageId":11,"blocks":[""" + FX_SUMMARY_CARD + """
            ],"latencyMs":4210}
            """;
    // resultRef·근거는 AI 테스트(test_chat_visa_tools)가 확인하는 calculate_e74_score 출력과 같다
    static final String STREAM_E74_EXAMPLE = """
            event: chat.started
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","userMessageId":10,"assistantMessageId":11}

            event: chat.tool_call
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","order":1,"toolName":"calculate_e74_score","status":"SUCCEEDED","resultRef":{"type":"E74_ASSESSMENT","id":77},"latencyMs":null}

            event: chat.delta
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","text":"Your E-7-4 mock score is 305 points "}

            event: chat.delta
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","text":"and the required total is 200."}

            event: chat.sources
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","sources":[{"type":"E74_ASSESSMENT","id":77,"chunkRef":null,"pageLabel":null}]}

            event: chat.done
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","assistantMessageId":11,"blocks":[""" + E74_SCORE_CARD + """
            ],"latencyMs":4210}
            """;
    // 상품 추천 도구는 resultRef 를 만들지 않는다. 근거(chat.sources, PRODUCT_DOCUMENT·chunkRef=productId)도 오지만
    // 계약 문서에 쪽(pageLabel) 값이 없어 예시에는 넣지 않는다.
    static final String STREAM_PRODUCT_EXAMPLE = """
            event: chat.started
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","userMessageId":10,"assistantMessageId":11}

            event: chat.tool_call
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","order":1,"toolName":"recommend_savings_products","status":"SUCCEEDED","resultRef":null,"latencyMs":null}

            event: chat.delta
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","text":"3 savings products fit your remaining 10 months of stay: "}

            event: chat.delta
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","text":"신한 땡겨요 적금, 정기적금 and 한 달부터 적금."}

            event: chat.done
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","assistantMessageId":11,"blocks":[""" + PRODUCT_RECOMMEND_CARD + """
            ],"latencyMs":4210}
            """;
    static final String STREAM_ERROR_EXAMPLE = """
            event: chat.started
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","userMessageId":10,"assistantMessageId":11}

            event: chat.error
            data: {"runId":"run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59","code":"AI_UPSTREAM_ERROR","message":"Could not finish the reply. Please try again."}
            """;

    private final ChatSessionService chatSessionService;
    private final ChatStreamService chatStreamService;

    public ChatController(ChatSessionService chatSessionService, ChatStreamService chatStreamService) {
        this.chatSessionService = chatSessionService;
        this.chatStreamService = chatStreamService;
    }

    @Operation(summary = "대화방 생성 (CHT-01)", description = """
            본문은 생략하거나 {"title": null} 로 보낸다. 제목 자동 생성은 아직 없음.
            응답의 id 가 대화방 ID(메시지 보내기·목록의 sessionId).
            앱 진입마다 새로 만들어도 된다. AI 에 이어지는 대화 맥락은 같은 대화방의 최근 완료된 질문·답변 3쌍뿐이라
            새 대화방은 맥락 없이 시작한다. 대화방 보관·삭제 API 는 없어 만든 대화방은 목록(CHT-02)에 남는다.""")
    @ApiResponse(responseCode = "201", description = "생성됨", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatSessionResponse.class),
            examples = @ExampleObject(name = "성공", value = SESSION_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "제목 200자 초과(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChatSessionResponse create(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                                      @Valid @RequestBody(required = false) ChatSessionCreateRequest request) {
        return chatSessionService.create(userId, request);
    }

    @Operation(summary = "대화방 목록 (CHT-02)",
            description = "본인의 ACTIVE 대화방. 마지막 메시지 시각(없으면 생성 시각) 최신순, 같으면 id 큰 순. "
                    + "다음 페이지는 page.nextCursor 를 cursor 로 보낸다")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "400", description = "size 가 1~100 밖이거나 cursor 형식 오류(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public ChatSessionListResponse list(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                                        @Parameter(description = "이전 응답의 page.nextCursor") @RequestParam(required = false) String cursor,
                                        @Parameter(description = "1~100, 기본 20") @RequestParam(defaultValue = "20") int size) {
        return chatSessionService.list(userId, cursor, size);
    }

    @Operation(summary = "메시지 목록 (CHT-03)",
            description = "최신 size 개를 오래된 순으로 준다. page.nextCursor 를 cursor 로 보내면 그 이전 메시지를 준다. "
                    + "근거(sources)는 메시지마다 함께 온다. ASSISTANT status: COMPLETED(완료), "
                    + "STREAMING(생성 중, content 는 빈 문자열), PARTIAL(앱 연결이 끊겨 중단, 그때까지의 content), "
                    + "FAILED(실패, 그때까지의 content). USER 는 항상 COMPLETED")
    @ApiResponse(responseCode = "200", description = "성공", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatMessageListResponse.class),
            examples = @ExampleObject(name = "성공", value = MESSAGES_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "size 가 1~100 밖이거나 cursor 형식 오류(details.fields)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없거나 남의 대화방",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{sessionId}/messages")
    public ChatMessageListResponse messages(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                                            @PathVariable Long sessionId,
                                            @Parameter(description = "이전 응답의 page.nextCursor") @RequestParam(required = false) String cursor,
                                            @Parameter(description = "1~100, 기본 30") @RequestParam(defaultValue = "30") int size) {
        return chatSessionService.messages(userId, sessionId, cursor, size);
    }

    @Operation(summary = "메시지 보내기·답변 스트리밍 (CHT-04)",
            description = """
                    text/event-stream 으로 답변을 보낸다. 이벤트 data 는 JSON 한 줄이고, 형식은 아래 Schemas 에 있다.
                    - chat.started (ChatStartedEvent): 바로 온다
                    - chat.tool_call (ChatToolCallEvent): 도구가 끝날 때마다. status 는 SUCCEEDED·FAILED·TIMEOUT·REJECTED.
                      진행 문구는 서버가 주지 않으므로 앱이 toolName 별로 정한다
                    - chat.delta (ChatDeltaEvent): 답변 조각. 이어 붙여 화면에 보여준다
                    - chat.sources (ChatSourcesEvent): 답변 근거
                    - chat.done (ChatDoneEvent): 완료
                    - chat.error (ChatErrorEvent): 실패. message 는 사용자 서비스 언어(en·ko·vi·zh) 안내 문구.
                      code 는 AI_UPSTREAM_ERROR(다시 시도해도 됨) 또는 INTERNAL_ERROR(한 번만 다시 시도 권장)
                    done 이나 error 가 마지막이고 그 뒤 연결이 닫힌다([DONE] 표시는 없다).
                    다시 시도할 때는 clientMessageId 를 새로 만든다(질문은 이미 저장되어 같은 값이면 409).
                    스트림 시작 전 오류(400·401·404·409)는 일반 JSON 오류로 온다.
                    앱이 중간에 끊으면 답변은 PARTIAL 로 저장되고 AI 생성도 멈춘다.""")
    @ApiResponse(responseCode = "200", description = "이벤트 스트림",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string"),
                    examples = {@ExampleObject(name = "송금 카드", value = STREAM_REMITTANCE_EXAMPLE),
                            @ExampleObject(name = "환율 카드(프롬프트 v4.4부터)", value = STREAM_FX_EXAMPLE),
                            @ExampleObject(name = "E-7-4 카드", value = STREAM_E74_EXAMPLE),
                            @ExampleObject(name = "상품 추천 카드(프롬프트 v4.5부터)", value = STREAM_PRODUCT_EXAMPLE),
                            @ExampleObject(name = "실패", value = STREAM_ERROR_EXAMPLE)}))
    @ApiResponse(responseCode = "400", description = "clientMessageId 1~64자, content 1~2000자(공백만 안 됨) 위반(details.fields)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없거나 남의 대화방, ARCHIVED 대화방",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "DUPLICATE_REQUEST: 같은 대화방에서 같은 clientMessageId(details.userMessageId). "
            + "AI 는 호출하지 않는다",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                             @PathVariable Long sessionId,
                             @Valid @RequestBody ChatStreamRequest request) {
        long receivedNanos = System.nanoTime();
        return chatStreamService.start(userId, sessionId, request, receivedNanos);
    }
}
