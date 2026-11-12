package com.hanstep.chat.service;

import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 마스킹 후보 규칙 비교용(IdentifierMaskerEvalPerfTest). main 코드가 아니며, 측정 기록을 다시 내려고 남겨 둔다.
 *
 * <ul>
 *   <li>legacy: fe2ab6e 시점 IdentifierMasker 규칙 복사본(6-7 등록번호, 13~16자리 연속 숫자)</li>
 *   <li>format(①): a 등록번호(6 + 하이픈/공백 + 7), b 구분된 숫자 묶음(구분자 한 종류, 묶음 2~7자리, 전체 10~16자리,
 *       앞뒤로 숫자·같은 구분자+숫자가 이어지지 않고 뒤에 ':' 없음), c 연속 숫자 10~16자리(앞뒤 0~1칸에 금액·단위 표기면 제외)</li>
 *   <li>context(②): ①과 같되 전체 10~12자리는 앞 20자 안에 계좌·카드 단어가 있을 때만 가림</li>
 *   <li>formatNarrowSpace(①'): ①과 같되 공백으로 나뉜 묶음은 3개 이상일 때만 b 로 가림(두 묶음 공백은 a 만). KEEP-21 을 보고 만든 사후 수정</li>
 * </ul>
 * 모두 숫자만 * 로 바꾸고 구분자·길이는 유지한다. 17자리 이상 연속 숫자는 그대로.
 */
final class MaskingCandidates {

    private static final Pattern LEGACY_REGISTRATION = Pattern.compile("(?<!\\d)\\d{6}-\\d{7}(?!\\d)");
    private static final Pattern LEGACY_LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{13,16}(?!\\d)");

    private static final Pattern REGISTRATION = Pattern.compile("(?<!\\d)\\d{6}[- ]\\d{7}(?!\\d)");
    private static final Pattern HYPHEN_GROUPS =
            Pattern.compile("(?<!\\d)(?<!\\d-)\\d{2,7}(?:-\\d{2,7})+(?!\\d)(?!-\\d)(?!:)");
    private static final Pattern SPACE_GROUPS =
            Pattern.compile("(?<!\\d)(?<!\\d )\\d{2,7}(?: \\d{2,7})+(?!\\d)(?! \\d)(?!:)");
    private static final Pattern SPACE_GROUPS_3 =
            Pattern.compile("(?<!\\d)(?<!\\d )\\d{2,7}(?: \\d{2,7}){2,}(?!\\d)(?! \\d)(?!:)");
    private static final String UNIT = "(?:원|(?i:krw|won|php)|₩|₱|%|점|개월)";
    private static final Pattern DIGITS = Pattern.compile("(?<!\\d)(?<!" + UNIT + " ?)\\d{10,16}(?!\\d)(?! ?" + UNIT + ")");

    private static final String[] CONTEXT_WORDS =
            {"account", "acct", "a/c", "bank", "card", "계좌", "통장", "카드", "은행", "bdo", "bpi"};
    private static final int CONTEXT_WINDOW = 20;

    private MaskingCandidates() {
    }

    static String legacy(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return replace(LEGACY_LONG_DIGITS, replace(LEGACY_REGISTRATION, text, always()), always());
    }

    /** ① 형식·길이: a + b + c. */
    static String format(String text) {
        return apply(text, (m, t) -> true);
    }

    /** ② 문맥: ① + 10~12자리는 앞 20자 안에 계좌·카드 단어가 있을 때만. */
    static String context(String text) {
        return apply(text, (m, t) -> {
            long digits = m.group().chars().filter(Character::isDigit).count();
            return digits > 12 || hasContextWord(t, m.start());
        });
    }

    /** ①' 공백 묶음 축소: ①과 같되 공백으로 나뉜 묶음은 3개 이상일 때만(두 묶음 공백은 a 등록번호 6+7만). */
    static String formatNarrowSpace(String text) {
        return apply(text, (m, t) -> true, SPACE_GROUPS_3);
    }

    private static String apply(String text, BiPredicate<MatchResult, String> accept) {
        return apply(text, accept, SPACE_GROUPS);
    }

    private static String apply(String text, BiPredicate<MatchResult, String> accept, Pattern spaceGroups) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        BiPredicate<MatchResult, String> grouped = (m, t) -> {
            long digits = m.group().chars().filter(Character::isDigit).count();
            return digits >= 10 && digits <= 16 && accept.test(m, t);
        };
        String out = replace(REGISTRATION, text, accept);
        out = replace(HYPHEN_GROUPS, out, grouped);
        out = replace(spaceGroups, out, grouped);
        return replace(DIGITS, out, accept);
    }

    private static boolean hasContextWord(String text, int start) {
        String before = text.substring(Math.max(0, start - CONTEXT_WINDOW), start).toLowerCase(Locale.ROOT);
        for (String word : CONTEXT_WORDS) {
            if (before.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static BiPredicate<MatchResult, String> always() {
        return (m, t) -> true;
    }

    private static String replace(Pattern pattern, String text, BiPredicate<MatchResult, String> accept) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String masked = accept.test(matcher, text) ? matcher.group().replaceAll("\\d", "*") : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
