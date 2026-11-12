package com.hanstep.chat.service;

import com.hanstep.perf.PerfSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 마스킹 방식 비교 평가(기록용, 판정 결과로 실패하지 않음). 기본 test 에서는 빠짐: ./gradlew perfTest --tests "*Masking*"
 *
 * <p>평가 세트 masking/masking_cases_v1.json(합성 값)으로 기존 규칙·후보 ①·후보 ②(MaskingCandidates)와
 * 현재 main 의 IdentifierMasker 를 같은 세트로 잰다. hold-out(masking/masking_holdout_v1.json)은 ①' 규칙을 정한 뒤에 받은
 * 별도 세트로, 후보 네 방식만 따로 집계한다. Spring·DB 없이 문자열 함수만 호출한다.
 * 누락: MASK 인데 mustMask 부분의 숫자가 하나라도 남음. 오탐: KEEP 인데 글자가 하나라도 바뀜. OUT_OF_SCOPE 는 기록만 한다.
 */
@Tag("perf")
class IdentifierMaskerEvalPerfTest {

    private static final String CASES = "/masking/masking_cases_v1.json";
    private static final String HOLDOUT = "/masking/masking_holdout_v1.json";
    private static final Path REPORT = Path.of("build", "perf", "masking-eval.md");

    record MaskingCase(String id, String text, String expect, String category, List<String> mustMask) {
    }

    record MaskingSet(String version, List<MaskingCase> cases) {
    }

    private record Result(String name, Map<String, int[]> byCategory, List<String> missed,
                          List<String> falsePositives, List<String> outOfScope, int maskTotal, int keepTotal) {
    }

    @Test
    void Masking_방식_비교() throws IOException {
        MaskingSet set = load();
        List<MaskingCase> holdout = loadHoldout();
        Map<String, UnaryOperator<String>> candidates = new LinkedHashMap<>();
        candidates.put("기존(fe2ab6e 규칙)", MaskingCandidates::legacy);
        candidates.put("① 형식·길이", MaskingCandidates::format);
        candidates.put("② 문맥", MaskingCandidates::context);
        candidates.put("①' 공백 묶음 축소", MaskingCandidates::formatNarrowSpace);
        Map<String, UnaryOperator<String>> withMain = new LinkedHashMap<>(candidates);
        withMain.put("main IdentifierMasker", IdentifierMasker::mask);

        StringBuilder md = new StringBuilder("# 챗봇 식별번호 마스킹 평가\n");
        report(md, "평가 세트 " + CASES + " " + set.version(), set.cases(), withMain);
        report(md, "hold-out " + HOLDOUT, holdout, candidates);

        PerfSupport.write(REPORT, md.toString());
        System.out.println(md);
    }

    /** 한 세트를 여러 방식으로 재서 요약표·분류별 표·사례 목록을 붙인다. */
    private static void report(StringBuilder md, String title, List<MaskingCase> cases,
                               Map<String, UnaryOperator<String>> methods) {
        List<Result> results = new ArrayList<>();
        methods.forEach((name, fn) -> results.add(evaluate(name, fn, cases)));

        md.append("\n# ").append(title).append(" (").append(cases.size()).append("건)\n\n")
                .append("| 방식 | 누락 / MASK | 오탐 / KEEP | 범위 밖(전화번호) 가려짐 |\n|---|---|---|---|\n");
        for (Result r : results) {
            long oosMasked = r.outOfScope().stream().filter(l -> l.endsWith("(바뀜)")).count();
            md.append("| ").append(r.name()).append(" | ").append(r.missed().size()).append(" / ").append(r.maskTotal())
                    .append(" | ").append(r.falsePositives().size()).append(" / ").append(r.keepTotal())
                    .append(" | ").append(oosMasked).append(" / ").append(r.outOfScope().size()).append(" |\n");
        }

        md.append("\n## 분류별 누락·오탐\n\n| 기대 / 분류 | 건수");
        results.forEach(r -> md.append(" | ").append(r.name()));
        md.append(" |\n|---|---");
        results.forEach(r -> md.append("|---"));
        md.append("|\n");
        for (String category : results.getFirst().byCategory().keySet()) {
            md.append("| ").append(category).append(" | ").append(results.getFirst().byCategory().get(category)[0]);
            for (Result r : results) {
                md.append(" | ").append(category.startsWith("OUT_OF_SCOPE") ? "-"
                        : String.valueOf(r.byCategory().get(category)[1]));
            }
            md.append(" |\n");
        }

        for (Result r : results) {
            md.append("\n## ").append(r.name()).append('\n');
            section(md, "누락 사례", r.missed());
            section(md, "오탐 사례", r.falsePositives());
            section(md, "범위 밖 결과", r.outOfScope());
        }
    }

    private static Result evaluate(String name, UnaryOperator<String> mask, List<MaskingCase> cases) {
        Map<String, int[]> byCategory = new TreeMap<>();
        List<String> missed = new ArrayList<>();
        List<String> falsePositives = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        int maskTotal = 0;
        int keepTotal = 0;
        for (MaskingCase c : cases) {
            String result = mask.apply(c.text());
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
        return new Result(name, byCategory, missed, falsePositives, outOfScope, maskTotal, keepTotal);
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
        md.append("\n### ").append(title).append(" (").append(lines.size()).append(")\n\n");
        if (lines.isEmpty()) {
            md.append("없음\n");
        }
        lines.forEach(l -> md.append("- ").append(l).append('\n'));
    }

    private static MaskingSet load() throws IOException {
        try (InputStream in = IdentifierMaskerEvalPerfTest.class.getResourceAsStream(CASES)) {
            if (in == null) {
                throw new IllegalStateException("missing " + CASES);
            }
            return JsonMapper.builder().build().readValue(in, MaskingSet.class);
        }
    }

    /** hold-out 은 받은 파일 그대로(버전 래퍼 없는 배열, 필드명은 같음). */
    private static List<MaskingCase> loadHoldout() throws IOException {
        try (InputStream in = IdentifierMaskerEvalPerfTest.class.getResourceAsStream(HOLDOUT)) {
            if (in == null) {
                throw new IllegalStateException("missing " + HOLDOUT);
            }
            return JsonMapper.builder().build().readValue(in, new TypeReference<List<MaskingCase>>() {
            });
        }
    }
}
