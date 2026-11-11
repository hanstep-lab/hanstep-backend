package com.hanstep.chat.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanstep.chat.support.FakeAgentServer;
import com.hanstep.common.ai.AiRestClientConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * JDK 클라이언트(AiRestClientConfig.requestFactory) read-timeout 이 "첫 응답까지"인지 "응답 전체"인지 확인.
 * 읽기 제한 1초, 가짜 서버는 300ms 간격으로 3초 동안 이벤트를 보낸다.
 * 결과: 이벤트가 계속 와도 1초쯤에 끊긴다 → 응답 전체 제한. 그래서 stream-read-timeout 은 답변 1개의 전체 상한으로 쓴다.
 */
class AiStreamReadTimeoutTest {

    @Test
    void read_timeout_은_응답_전체에_걸린다() throws Exception {
        try (FakeAgentServer server = new FakeAgentServer()) {
            server.script((request, out) -> {
                for (int i = 0; i < 10; i++) {
                    out.event("agent.delta", "{\"text\":\"" + i + "\"}");
                    Thread.sleep(300);
                }
                out.event("agent.done", "{}");
            });
            RestClient client = RestClient.builder()
                    .requestFactory(AiRestClientConfig.requestFactory(Duration.ofSeconds(1), Duration.ofSeconds(1)))
                    .baseUrl(server.baseUrl()).build();
            List<String> dataLines = new CopyOnWriteArrayList<>();

            long started = System.nanoTime();
            assertThatThrownBy(() -> client.post().uri("/stream").body("{}").exchange((request, response) -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            dataLines.add(line);
                        }
                    }
                }
                return null;
            })).isInstanceOf(ResourceAccessException.class);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            System.out.printf("[read-timeout] cut after %d ms, events before cut=%d%n", elapsedMs, dataLines.size());

            // 300ms 간격이라 이벤트 사이 공백은 제한(1초)보다 짧다. 그래도 1초 근처에서 끊김
            assertThat(dataLines).hasSizeBetween(3, 5);
            assertThat(elapsedMs).isBetween(900L, 2_000L);
        }
    }
}
