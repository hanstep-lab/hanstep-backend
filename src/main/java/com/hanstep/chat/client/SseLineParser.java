package com.hanstep.chat.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * text/event-stream 을 줄 단위로 읽어 이벤트로 묶는다.
 * event:·data: 를 모으다가 빈 줄에서 이벤트 하나를 낸다. 여러 data 줄은 \n 으로 잇는다.
 * ":" 로 시작하는 줄(연결 유지용)과 id:·retry: 는 무시한다. 줄 단위로 읽으므로 한 조각에 이벤트가 여러 개 오거나
 * 이벤트가 중간에 잘려 와도 상관없다.
 */
public final class SseLineParser {

    public record Event(String name, String data) {
    }

    /** 이벤트를 받는 쪽. false 를 돌려주면 더 읽지 않는다. */
    @FunctionalInterface
    public interface Listener {
        boolean onEvent(Event event) throws IOException;
    }

    private SseLineParser() {
    }

    /** 스트림 끝까지(또는 listener 가 false 를 줄 때까지) 읽는다. 끝까지 읽었으면 true. */
    public static boolean read(InputStream in, Listener listener) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String name = null;
        StringBuilder data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data != null) {
                    if (!listener.onEvent(new Event(name == null ? "message" : name, data.toString()))) {
                        return false;
                    }
                }
                name = null;
                data = null;
            } else if (line.startsWith(":")) {
                continue;
            } else {
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (field.equals("event")) {
                    name = value;
                } else if (field.equals("data")) {
                    data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
                }
            }
        }
        return true;
    }
}
