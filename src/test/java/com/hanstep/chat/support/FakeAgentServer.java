package com.hanstep.chat.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 가짜 AI 서버(INT-05 자리). 실제 AI·외부 API 는 부르지 않는다.
 * 요청마다 가상 스레드 하나로 처리해 동시 연결 측정에서 가짜 서버가 병목이 되지 않게 한다.
 * 동작은 {@link #script(Script)} 로 테스트마다 바꾼다.
 */
public final class FakeAgentServer implements AutoCloseable {

    /** 요청 하나를 처리하는 방식. 본문 쓰기는 {@link Writer} 로 한다. */
    @FunctionalInterface
    public interface Script {
        void run(Received request, Writer out) throws Exception;
    }

    public record Received(String path, String body, String internalToken, String requestId) {
    }

    /** SSE 응답 쓰기. 쓰기가 실패하면(상대가 끊음) IOException. */
    public static final class Writer {

        private final HttpExchange exchange;
        private boolean started;

        Writer(HttpExchange exchange) {
            this.exchange = exchange;
        }

        public void status(int code) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, -1);
            started = true;
        }

        public void raw(String text) throws IOException {
            start();
            OutputStream body = exchange.getResponseBody();
            body.write(text.getBytes(StandardCharsets.UTF_8));
            body.flush();
        }

        public void event(String name, String json) throws IOException {
            raw("event: " + name + "\ndata: " + json + "\n\n");
        }

        void start() throws IOException {
            if (!started) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                started = true;
            }
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile Script script = (request, out) -> out.status(500);

    public FakeAgentServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1000);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeAgentServer script(Script script) {
        this.script = script;
        return this;
    }

    public List<Received> received() {
        return received;
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    public void reset() {
        received.clear();
        maxInFlight.set(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Received request = new Received(exchange.getRequestURI().getPath(), body,
                    exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                    exchange.getRequestHeaders().getFirst("X-Request-Id"));
            received.add(request);
            Writer out = new Writer(exchange);
            try {
                script.run(request, out);
                out.start();
            } catch (IOException e) {
                // 상대(Spring)가 연결을 끊음. 스크립트가 직접 잡아 기록할 수도 있다
            } catch (Exception e) {
                throw new IOException(e);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
