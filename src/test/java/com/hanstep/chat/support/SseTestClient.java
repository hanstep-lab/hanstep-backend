package com.hanstep.chat.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** 앱 역할 테스트 클라이언트. CHT-04 를 실제 HTTP 로 부르고 SSE 이벤트를 도착 시각과 함께 모은다. */
public final class SseTestClient {

    public record Event(String name, String data, long atMs) {
    }

    public record Result(int status, String contentType, List<Event> events, String body, long startNanos) {

        public List<String> names() {
            return events.stream().map(Event::name).toList();
        }

        public Event first(String name) {
            return events.stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
        }
    }

    private final HttpClient client;
    private final String baseUrl;

    public SseTestClient(HttpClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    public HttpClient http() {
        return client;
    }

    public static String body(String clientMessageId, String content) {
        return "{\"clientMessageId\":" + quote(clientMessageId) + ",\"content\":" + quote(content) + "}";
    }

    /** 끝까지 읽는다. stopWhen 이 참이 되면 그 자리에서 입력 스트림을 닫는다(앱 연결 끊김). */
    public Result stream(String bearer, long sessionId, String json, Predicate<List<Event>> stopWhen)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/chat/sessions/" + sessionId
                        + "/messages/stream"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", bearer)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        long started = System.nanoTime();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        List<Event> events = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        InputStream in = response.body();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String name = null;
            String data = null;
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
                if (line.isEmpty()) {
                    if (data != null) {
                        events.add(new Event(name, data, (System.nanoTime() - started) / 1_000_000));
                        if (stopWhen != null && stopWhen.test(events)) {
                            break;
                        }
                    }
                    name = null;
                    data = null;
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data = line.substring(5).strip();
                }
            }
        }
        return new Result(response.statusCode(), contentType, events, body.toString(), started);
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
