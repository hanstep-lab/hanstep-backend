package com.hanstep.chat.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * INT-05 POST /internal/v1/agent/chat/stream 호출. 응답(text/event-stream)을 줄 단위로 읽어 이벤트를 넘긴다.
 * 호출한 스레드에서 스트림이 끝날 때까지 막혀 있다. 다른 스레드에서 {@link Handle#close()} 하면 입력 스트림을 닫아
 * 읽기를 멈추고 AI 쪽 연결도 끊긴다(AI 가 생성을 멈추게).
 */
@Component
public class AgentStreamClient {

    static final String PATH = "/internal/v1/agent/chat/stream";

    private final RestClient aiStreamRestClient;

    public AgentStreamClient(@Qualifier("aiStreamRestClient") RestClient aiStreamRestClient) {
        this.aiStreamRestClient = aiStreamRestClient;
    }

    public record ChatRequest(String runId, Long userId, Long sessionId, String language, List<HistoryItem> history,
                              String message) {
    }

    public record HistoryItem(String role, String content) {
    }

    /** AI 가 2xx 가 아닌 상태를 돌려줌. */
    public static class AgentHttpException extends RuntimeException {

        public AgentHttpException(int status) {
            super("agent stream returned HTTP " + status);
        }
    }

    /** 진행 중인 호출의 입력 스트림. 닫기는 여러 번·어느 스레드에서 해도 된다. */
    public static final class Handle {

        private InputStream in;
        private boolean closed;

        synchronized void attach(InputStream in) throws IOException {
            this.in = in;
            if (closed) {
                in.close();
            }
        }

        public void close() {
            InputStream target;
            synchronized (this) {
                closed = true;
                target = in;
            }
            if (target != null) {
                try {
                    target.close();
                } catch (IOException ignored) {
                    // 이미 닫힘
                }
            }
        }

        public synchronized boolean isClosed() {
            return closed;
        }
    }

    /** 스트림을 끝까지(또는 listener 가 멈출 때까지) 읽는다. 연결·HTTP·읽기 오류는 예외로 나온다. */
    public void stream(ChatRequest request, Handle handle, SseLineParser.Listener listener) {
        aiStreamRestClient.post().uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(request)
                .exchange((req, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new AgentHttpException(response.getStatusCode().value());
                    }
                    InputStream body = response.getBody();
                    handle.attach(body);
                    SseLineParser.read(body, listener);
                    return null;
                });
    }
}
