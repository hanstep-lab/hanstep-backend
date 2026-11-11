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

    private final ChatSessionService chatSessionService;
    private final ChatStreamService chatStreamService;

    public ChatController(ChatSessionService chatSessionService, ChatStreamService chatStreamService) {
        this.chatSessionService = chatSessionService;
        this.chatStreamService = chatStreamService;
    }

    @Operation(summary = "대화방 생성 (CHT-01)", description = "본문은 생략하거나 {\"title\": null} 로 보낸다. 제목 자동 생성은 아직 없음")
    @ApiResponse(responseCode = "201", description = "생성됨")
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
                    + "근거(sources)는 메시지마다 함께 온다")
    @ApiResponse(responseCode = "200", description = "성공")
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
                    text/event-stream 으로 답변을 보낸다. 이벤트 data 는 JSON 한 줄이다.
                    - chat.started {runId, userMessageId, assistantMessageId}: 바로 온다
                    - chat.tool_call {runId, order, toolName, status, resultRef, latencyMs}
                    - chat.delta {runId, text}: 답변 조각. 이어 붙여 화면에 보여준다
                    - chat.sources {runId, sources:[{type,id,chunkRef,pageLabel}]}
                    - chat.done {runId, assistantMessageId, blocks, latencyMs}
                    - chat.error {runId, code, message}: code 는 AI 가 준 값 또는 AI_UPSTREAM_ERROR
                    done 이나 error 가 마지막이고 그 뒤 연결이 닫힌다([DONE] 표시는 없다).
                    스트림 시작 전 오류(400·401·404·409)는 일반 JSON 오류로 온다.
                    앱이 중간에 끊으면 답변은 PARTIAL 로 저장되고 AI 생성도 멈춘다.""")
    @ApiResponse(responseCode = "200", description = "이벤트 스트림",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string")))
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
