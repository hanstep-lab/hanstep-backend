package com.hanstep.chat.service;

import com.hanstep.perf.PerfSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * IdentifierMasker 마스킹 평가(기준선 기록용, 판정 결과로 실패하지 않음). 기본 test 에서는 빠짐: ./gradlew perfTest --tests "*Masking*"
 *
 * <p>평가 세트 masking/masking_cases_v1.json(합성 값). Spring·DB 없이 IdentifierMasker.mask 만 호출한다.
 * 누락: MASK 인데 mustMask 부분의 숫자가 하나라도 남음. 오탐: KEEP 인데 글자가 하나라도 바뀜. OUT_OF_SCOPE 는 기록만 한다.
 */
@Tag("perf")
class IdentifierMaskerEvalPerfTest {

    private static final String CASES = "/masking/masking_cases_v1.json";
    private static final Path REPORT = Path.of("build", "perf", "masking-eval.md");

    record MaskingCase(String id, String text, String expect, String category, List<String> mustMask) {
    }

    @Test
    void Masking_기준선() throws IOException {
        List<MaskingCase> cases = load();

        Map<String, int[]> byCategory = new TreeMap<>();
        List<String> missed = new ArrayList<>();
        List<String> falsePositives = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        int maskTotal = 0;
        int keepTotal = 0;

        for (MaskingCase c : cases) {
            String result = IdentifierMasker.mask(c.text());
            int[] counts = byCategory.computeIfAbsent(c.expect() + " / " + c.category(), k -> new int[2]);
            counts[0]++;
            switch (c.expect()) {
                case "MASK" -> {
                    maskTotal++;
                    if (isMissed(c, result)) {
                        counts[1]++;
                        missed.add(line(c, result));
                    }
                }
                case "KEEP" -> {
                    keepTotal++;
                    if (!result.equals(c.text())) {
                        counts[1]++;
                        falsePositives.add(line(c, result));
                    }
                }
                case "OUT_OF_SCOPE" -> outOfScope.add(line(c, result) + (result.equals(c.text()) ? " (그대로)" : " (바뀜)"));
                default -> throw new IllegalStateException("unknown expect: " + c.id());
            }
        }

        StringBuilder md = new StringBuilder("# 챗봇 식별번호 마스킹 평가 (기준선)\n\n")
                .append("| 항목 | 값 |\n|---|---|\n")
                .append("| 평가 세트 | ").append(CASES).append(" (").append(cases.size()).append("건) |\n")
                .append("| 누락 | ").append(missed.size()).append(" / ").append(maskTotal).append(" (MASK) |\n")
                .append("| 오탐 | ").append(falsePositives.size()).append(" / ").append(keepTotal).append(" (KEEP) |\n")
                .append("| 범위 밖 | ").append(outOfScope.size()).append("건 (판정 제외) |\n\n")
                .append("## 분류별\n\n| 기대 / 분류 | 건수 | 누락·오탐 |\n|---|---|---|\n");
        byCategory.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v[0]).append(" | ")
                .append(k.startsWith("OUT_OF_SCOPE") ? "-" : String.valueOf(v[1])).append(" |\n"));
        section(md, "누락 사례", missed);
        section(md, "오탐 사례", falsePositives);
        section(md, "범위 밖 결과", outOfScope);

        PerfSupport.write(REPORT, md.toString());
        System.out.println(md);
    }

    /** mustMask 위치의 숫자가 결과에 남았는지. 마스킹은 길이를 유지하므로 같은 위치를 본다(길이가 다르면 원문 부분이 남았는지로 판단). */
    private static boolean isMissed(MaskingCase c, String result) {
        for (String part : c.mustMask()) {
            int at = c.text().indexOf(part);
            if (at < 0) {
                throw new IllegalStateException("mustMask not in text: " + c.id());
            }
            if (result.length() != c.text().length()) {
                if (result.contains(part)) {
                    return true;
                }
                continue;
            }
            if (result.substring(at, at + part.length()).chars().anyMatch(Character::isDigit)) {
                return true;
            }
        }
        return false;
    }

    private static String line(MaskingCase c, String result) {
        return "`" + c.id() + "` " + c.category() + ": `" + c.text() + "` → `" + result + "`";
    }

    private static void section(StringBuilder md, String title, List<String> lines) {
        md.append("\n## ").append(title).append(" (").append(lines.size()).append(")\n\n");
        if (lines.isEmpty()) {
            md.append("없음\n");
        }
        lines.forEach(l -> md.append("- ").append(l).append('\n'));
    }

    private static List<MaskingCase> load() throws IOException {
        try (InputStream in = IdentifierMaskerEvalPerfTest.class.getResourceAsStream(CASES)) {
            if (in == null) {
                throw new IllegalStateException("missing " + CASES);
            }
            return JsonMapper.builder().build().readValue(in, new TypeReference<List<MaskingCase>>() {
            });
        }
    }
}
