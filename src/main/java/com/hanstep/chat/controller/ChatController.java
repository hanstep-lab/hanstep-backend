package com.hanstep.chat.controller;

import com.hanstep.chat.dto.ChatMessageListResponse;
import com.hanstep.chat.dto.ChatSessionCreateRequest;
import com.hanstep.chat.dto.ChatSessionListResponse;
import com.hanstep.chat.dto.ChatSessionResponse;
import com.hanstep.chat.service.ChatSessionService;
import com.hanstep.common.error.ErrorResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Chat", description = "AI 챗봇 대화방·메시지")
@RestController
@RequestMapping("/api/v1/chat/sessions")
public class ChatController {

    private final ChatSessionService chatSessionService;

    public ChatController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
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
}
