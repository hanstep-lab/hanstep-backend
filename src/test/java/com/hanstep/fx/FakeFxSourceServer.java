package com.hanstep.fx;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 수출입은행·Frankfurter 를 대신하는 로컬 HTTP 서버(JVM 에 1개). 테스트 클래스들이 같은 주소를 써서 스프링 컨텍스트를 같이 쓴다.
 * 날짜는 2031년을 써서 로컬 DB 의 04 시드·수집한 실데이터와 키가 겹치지 않게 한다.
 */
public final class FakeFxSourceServer {

    public static final String KOREAEXIM_KEY = "test-koreaexim-key-0123456789ab";
    public static final String KOREAEXIM_PATH = "/site/program/financial/exchangeJSON";
    public static final String FRANKFURTER_PATH = "/v2/providers/ecb/rates";

    private static final HttpServer SERVER = start();

    /** searchdate → 응답 본문(JSON). 기본: 그 날짜 USD 1건. */
    public static volatile Function<LocalDate, String> koreaexim = FakeFxSourceServer::usd;
    /** 쿼리 → 응답 본문(JSON). 기본: from~to(없으면 2031-01-03) 평일마다 PHP 1건. */
    public static volatile Function<Map<String, String>, String> frankfurter = FakeFxSourceServer::php;
    public static volatile int koreaeximStatus = 200;
    public static volatile int frankfurterStatus = 200;
    public static final List<String> KOREAEXIM_QUERIES = new CopyOnWriteArrayList<>();
    public static final List<String> FRANKFURTER_QUERIES = new CopyOnWriteArrayList<>();

    private FakeFxSourceServer() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("hanstep.fx-collector.koreaexim.base-url", () -> base);
        registry.add("hanstep.fx-collector.koreaexim.api-key", () -> KOREAEXIM_KEY);
        registry.add("hanstep.fx-collector.frankfurter.base-url", () -> base + "/v2");
        registry.add("hanstep.fx-collector.currencies", () -> "USD,PHP");
        registry.add("hanstep.fx-collector.backfill.call-interval", () -> "0ms");
    }

    public static void reset() {
        koreaexim = FakeFxSourceServer::usd;
        frankfurter = FakeFxSourceServer::php;
        koreaeximStatus = 200;
        frankfurterStatus = 200;
        KOREAEXIM_QUERIES.clear();
        FRANKFURTER_QUERIES.clear();
    }

    /** 실제 응답 형식(쉼표 숫자). 값은 날짜마다 조금씩 다르다. */
    public static String usd(LocalDate date) {
        int day = date.getDayOfMonth();
        return """
                [{"result":1,"cur_unit":"KRW","ttb":"0","tts":"0","deal_bas_r":"1"},
                 {"result":1,"cur_unit":"USD","ttb":"1,3%02d.07","tts":"1,3%02d.72","deal_bas_r":"1,3%02d.4"}]"""
                .formatted(day, day, day);
    }

    public static String php(Map<String, String> query) {
        LocalDate from = LocalDate.parse(query.getOrDefault("from", "2031-01-03"));
        LocalDate to = LocalDate.parse(query.getOrDefault("to", from.toString()));
        StringBuilder body = new StringBuilder("[");
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() >= 6) {
                continue;
            }
            if (body.length() > 1) {
                body.append(',');
            }
            body.append("{\"date\":\"%s\",\"base\":\"PHP\",\"quote\":\"KRW\",\"rate\":20.%03d}"
                    .formatted(d, d.getDayOfMonth()));
        }
        return body.append(']').toString();
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(KOREAEXIM_PATH, exchange -> {
                Map<String, String> query = query(exchange);
                KOREAEXIM_QUERIES.add(exchange.getRequestURI().getRawQuery());
                LocalDate date = LocalDate.parse(query.get("searchdate"), DateTimeFormatter.BASIC_ISO_DATE);
                respond(exchange, koreaeximStatus, koreaexim.apply(date));
            });
            server.createContext(FRANKFURTER_PATH, exchange -> {
                FRANKFURTER_QUERIES.add(exchange.getRequestURI().getRawQuery());
                respond(exchange, frankfurterStatus, frankfurter.apply(query(exchange)));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> query = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                query.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return query;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
