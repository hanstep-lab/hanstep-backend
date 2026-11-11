package com.hanstep.chat.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseLineParserTest {

    private static final String STREAM = """
            : keep-alive

            event: agent.delta
            data: {"text":"A"}

            event: agent.delta
            data: {"text":"B"}

            event: agent.unknown
            data: {}

            event: agent.done
            data: {"content":
            data: "AB"}

            """;

    @Test
    void 한_조각에_여러_이벤트() throws IOException {
        assertThat(parse(new ByteArrayInputStream(STREAM.getBytes(StandardCharsets.UTF_8)))).containsExactly(
                new SseLineParser.Event("agent.delta", "{\"text\":\"A\"}"),
                new SseLineParser.Event("agent.delta", "{\"text\":\"B\"}"),
                new SseLineParser.Event("agent.unknown", "{}"),
                new SseLineParser.Event("agent.done", "{\"content\":\n\"AB\"}"));
    }

    @Test
    void 이벤트가_바이트_단위로_잘려_와도_같다() throws IOException {
        byte[] bytes = STREAM.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        for (int size : new int[] {1, 2, 3, 7, 13}) {
            assertThat(parse(new Chunked(bytes, size))).as("chunk %d", size).hasSize(4)
                    .last().isEqualTo(new SseLineParser.Event("agent.done", "{\"content\":\n\"AB\"}"));
        }
    }

    @Test
    void 끝_빈_줄이_없는_마지막_이벤트는_버리고_listener_가_false면_멈춘다() throws IOException {
        String text = "event: agent.delta\ndata: {\"text\":\"A\"}\n\nevent: agent.done\ndata: {}";
        assertThat(parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))).hasSize(1);

        List<SseLineParser.Event> seen = new ArrayList<>();
        boolean reachedEnd = SseLineParser.read(new ByteArrayInputStream(STREAM.getBytes(StandardCharsets.UTF_8)),
                e -> seen.add(e) && seen.size() < 2);
        assertThat(reachedEnd).isFalse();
        assertThat(seen).hasSize(2);
    }

    private static List<SseLineParser.Event> parse(InputStream in) throws IOException {
        List<SseLineParser.Event> events = new ArrayList<>();
        SseLineParser.read(in, events::add);
        return events;
    }

    /** read 한 번에 최대 size 바이트만 주는 스트림(네트워크 조각 흉내). */
    private static final class Chunked extends InputStream {

        private final byte[] bytes;
        private final int size;
        private int pos;

        Chunked(byte[] bytes, int size) {
            this.bytes = bytes;
            this.size = size;
        }

        @Override
        public int read() {
            return pos < bytes.length ? bytes[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= bytes.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, size), bytes.length - pos);
            System.arraycopy(bytes, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
