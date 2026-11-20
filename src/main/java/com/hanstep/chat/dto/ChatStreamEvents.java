package com.hanstep.chat.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * CHT-04 스트림 이벤트의 data(JSON 한 줄). Swagger 는 SSE 이벤트 구조를 그리지 못하므로
 * ChatOpenApiCustomizer 가 이 record 들을 Schemas 에 따로 등록한다.
 */
public final class ChatStreamEvents {

    /** 메시지 목록(CHT-03)과 chat.done 의 blocks 설명. 카드별 예시는 CHT-04 응답 예시에 있다. */
    public static final String BLOCKS_DESCRIPTION = "AI가 도구 성공 시 코드로 만든 카드 배열(그대로 저장·전달). 없으면 null. "
            + "type: REMITTANCE_CONFIRM(quote_remittance), E74_SCORE_CARD·MISSING_INPUTS(calculate_e74_score), "
            + "FX_SUMMARY_CARD(fx_rate_summary, 프롬프트 v4.4부터), "
            + "PRODUCT_RECOMMEND_CARD(recommend_savings_products, 프롬프트 v4.5부터, 기권이면 products=[]·abstainReason). "
            + "E74_SCORE_CARD 의 ruleStatus 는 DRAFT 또는 APPROVED. "
            + "MISSING_INPUTS 는 fields[]: {field, question}(판정에 더 필요한 입력)";

    private ChatStreamEvents() {
    }

    @Schema(name = "ChatStartedEvent", description = "event: chat.started. 요청을 받자마자 한 번 온다")
    public record Started(
            @Schema(description = "실행 ID. 이 답변의 다른 이벤트·메시지 목록의 runId 와 같다",
                    example = "run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59")
            String runId,

            @Schema(description = "저장된 질문(USER) 메시지 ID", example = "10")
            long userMessageId,

            @Schema(description = "만들어질 답변(ASSISTANT) 메시지 ID", example = "11")
            long assistantMessageId
    ) {
    }

    @Schema(name = "ChatToolCallEvent", description = "event: chat.tool_call. AI 가 도구(계산·조회)를 하나 끝낼 때마다 온다. "
            + "끝난 뒤에 오므로 진행 중 상태는 없다. 화면 문구(예: \"재무 분석을 확인했어요\")는 서버가 주지 않으며 "
            + "앱이 toolName 별로 정한다. 한 답변에 도구 이벤트가 여러 번 올 수 있음")
    public record ToolCall(
            @Schema(example = "run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59")
            String runId,

            @Schema(description = "이 답변 안에서 도구 호출 순서(1부터)", example = "1")
            int order,

            @Schema(description = "도구 이름(AI 서버가 정함). 앱은 모르는 이름이 와도 무시하거나 기본 문구를 쓴다",
                    example = "get_financial_analysis")
            String toolName,

            @Schema(description = "도구 결과", example = "SUCCEEDED",
                    allowableValues = {"SUCCEEDED", "FAILED", "TIMEOUT", "REJECTED"})
            String status,

            @Schema(description = "도구가 만든·읽은 결과 행. 없으면 null", nullable = true)
            ResultRef resultRef,

            @Schema(description = "도구 실행 시간 ms. 없으면 null", nullable = true, example = "320")
            Integer latencyMs
    ) {
    }

    @Schema(name = "ChatToolResultRef", description = "도구 결과 행 참조")
    public record ResultRef(
            @Schema(description = "결과 종류", example = "FINANCIAL_ANALYSIS")
            String type,

            @Schema(description = "결과 행 ID", example = "7")
            Long id
    ) {
    }

    @Schema(name = "ChatDeltaEvent", description = "event: chat.delta. 답변 조각. 받은 순서대로 이어 붙여 보여 준다")
    public record Delta(
            @Schema(example = "run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59")
            String runId,

            @Schema(example = "You can save ")
            String text
    ) {
    }

    @Schema(name = "ChatSourcesEvent", description = "event: chat.sources. 답변 근거. 메시지 목록의 sources 와 같은 형식")
    public record Sources(
            @Schema(example = "run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59")
            String runId,

            @ArraySchema(arraySchema = @Schema(description = "근거. 없으면 빈 배열"))
            List<ChatSource> sources
    ) {
    }

    @Schema(name = "ChatDoneEvent", description = "event: chat.done. 답변 완료. 이 이벤트 뒤 연결이 닫힌다")
    public record Done(
            @Schema(example = "run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59")
            String runId,

            @Schema(example = "11")
            long assistantMessageId,

            @Schema(description = BLOCKS_DESCRIPTION, nullable = true)
            JsonNode blocks,

            @Schema(description = "요청 수신부터 완료까지 ms", example = "4210")
            int latencyMs
    ) {
    }

    @Schema(name = "ChatErrorEvent", description = "event: chat.error. 답변 실패. 이 이벤트 뒤 연결이 닫힌다. "
            + "다시 시도할 때는 clientMessageId 를 새로 만들어 보낸다(질문은 이미 저장되어 같은 값이면 409 DUPLICATE_REQUEST)")
    public record ErrorEvent(
            @Schema(example = "run_4f1c2b7e9d0a4c3b8e6f5a1d2c3b4a59")
            String runId,

            @Schema(description = "AI_UPSTREAM_ERROR: AI 연결·응답 오류(AI 가 보낸 오류, 서버가 바빠 받지 못한 경우 포함). "
                    + "대부분 일시적이라 다시 시도해도 된다. "
                    + "INTERNAL_ERROR: AI 오류가 아닌 내부 예외. 다시 시도는 되지만 반복될 수 있어 한 번만 권장",
                    example = "AI_UPSTREAM_ERROR", allowableValues = {"AI_UPSTREAM_ERROR", "INTERNAL_ERROR"})
            String code,

            @Schema(description = "사용자 서비스 언어(en·ko·vi·zh, 모르면 en)의 안내 문구. 그대로 보여 줘도 된다",
                    example = "Could not finish the reply. Please try again.")
            String message
    ) {
    }
}
