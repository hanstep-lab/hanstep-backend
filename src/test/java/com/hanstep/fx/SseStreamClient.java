package com.hanstep.fx;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 앱 역할 스트림 클라이언트. GET 으로 SSE 를 열어 두고 이벤트·주석을 받는 대로 모은다(읽기는 데몬 스레드).
 * close() 는 입력 스트림을 닫아 앱 연결 끊김을 흉내 낸다.
 */
public final class SseStreamClient implements AutoCloseable {

    public record Event(String id, String name, String data, long atNanos) {
    }

    private final HttpResponse<InputStream> response;
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final List<String> comments = new CopyOnWriteArrayList<>();
    private final CountDownLatch ended = new CountDownLatch(1);

    private SseStreamClient(HttpResponse<InputStream> response) {
        this.response = response;
        Thread reader = new Thread(this::read, "sse-test-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public static SseStreamClient open(HttpClient client, String url, String bearer)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/event-stream")
                .GET();
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return new SseStreamClient(client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream()));
    }

    public int status() {
        return response.statusCode();
    }

    public String contentType() {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public List<Event> events(String name) {
        return events.stream().filter(e -> name.equals(e.name())).toList();
    }

    public List<String> comments() {
        return List.copyOf(comments);
    }

    /** name 이벤트가 count 개 이상 올 때까지 기다린다. 받은 그 이벤트들을 돌려준다(모자라면 받은 만큼). */
    public List<Event> await(String name, int count, Duration timeout) throws InterruptedException {
        until(() -> events(name).size() >= count, timeout);
        return events(name);
    }

    /** 서버가 스트림을 닫을 때까지(EOF) 기다린다. */
    public boolean awaitEnd(Duration timeout) throws InterruptedException {
        return ended.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean ended() {
        return ended.getCount() == 0;
    }

    @Override
    public void close() throws IOException {
        response.body().close();
    }

    /** 조건이 참이 될 때까지 20ms 마다 본다. 시간 안에 참이 되면 true. */
    public static boolean until(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }

    private void read() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String id = null;
            String name = null;
            StringBuilder data = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data != null) {
                        events.add(new Event(id, name, data.toString(), System.nanoTime()));
                    }
                    id = null;
                    name = null;
                    data = null;
                } else if (line.startsWith(":")) {
                    comments.add(line);
                } else if (line.startsWith("id:")) {
                    id = value(line, 3);
                } else if (line.startsWith("event:")) {
                    name = value(line, 6);
                } else if (line.startsWith("data:")) {
                    data = data == null ? new StringBuilder() : data.append('\n');
                    data.append(value(line, 5));
                }
            }
        } catch (IOException e) {
            // close() 로 닫았거나 연결이 끊김
        } finally {
            ended.countDown();
        }
    }

    /** SSE 규칙: 콜론 뒤 공백 하나는 값에 넣지 않는다. */
    private static String value(String line, int start) {
        return line.startsWith(" ", start) ? line.substring(start + 1) : line.substring(start);
    }
}
