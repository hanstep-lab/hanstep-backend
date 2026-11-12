package com.hanstep.chat.service;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 본문 저장 전 식별번호 마스킹(USER·ASSISTANT 둘 다). 누락이 오탐보다 위험하므로 계좌·카드 형식을 넓게 가린다.
 * <ol>
 *   <li>외국인등록번호: 6자리 + (하이픈 또는 공백 한 칸) + 7자리</li>
 *   <li>하이픈으로 나뉜 숫자 묶음: 묶음 2개 이상, 묶음마다 2~7자리, 전체 10~16자리(계좌 3-3-6·카드 4-4-4-4 등)</li>
 *   <li>공백으로 나뉜 숫자 묶음: 위와 같되 묶음 3개 이상일 때만(두 묶음 공백은 1의 등록번호만. "202611 1234" 같은 숫자 보호)</li>
 *   <li>연속 숫자 10~16자리. 바로 앞뒤(공백 0~1칸)에 원·KRW·won·PHP·₩·₱·%·점·개월이 붙은 금액·단위는 제외</li>
 * </ol>
 * 2·3은 앞뒤로 숫자나 "같은 구분자+숫자"가 이어지면(더 긴 묶음의 일부) 건드리지 않고, 바로 뒤에 ':'이 오면(시각) 제외한다.
 * 17자리 이상 연속 숫자는 그대로. 전화번호(010-1234-5678 등)도 가려질 수 있으나 범위 밖이라 허용한다.
 * 숫자만 * 로 바꾸고 하이픈·공백·길이는 그대로 둔다. 규칙 비교 기록은 test 의 MaskingCandidates·IdentifierMaskerEvalPerfTest.
 */
public final class IdentifierMasker {

    private static final Pattern REGISTRATION = Pattern.compile("(?<!\\d)\\d{6}[- ]\\d{7}(?!\\d)");
    private static final Pattern HYPHEN_GROUPS =
            Pattern.compile("(?<!\\d)(?<!\\d-)\\d{2,7}(?:-\\d{2,7})+(?!\\d)(?!-\\d)(?!:)");
    private static final Pattern SPACE_GROUPS =
            Pattern.compile("(?<!\\d)(?<!\\d )\\d{2,7}(?: \\d{2,7}){2,}(?!\\d)(?! \\d)(?!:)");
    private static final String UNIT = "(?:원|(?i:krw|won|php)|₩|₱|%|점|개월)";
    private static final Pattern DIGITS =
            Pattern.compile("(?<!\\d)(?<!" + UNIT + " ?)\\d{10,16}(?!\\d)(?! ?" + UNIT + ")");

    private IdentifierMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = replace(REGISTRATION, text, group -> true);
        out = replace(HYPHEN_GROUPS, out, IdentifierMasker::hasTenToSixteenDigits);
        out = replace(SPACE_GROUPS, out, IdentifierMasker::hasTenToSixteenDigits);
        return replace(DIGITS, out, group -> true);
    }

    private static boolean hasTenToSixteenDigits(String group) {
        long digits = group.chars().filter(Character::isDigit).count();
        return digits >= 10 && digits <= 16;
    }

    private static String replace(Pattern pattern, String text, Predicate<String> accept) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String group = matcher.group();
            matcher.appendReplacement(out, accept.test(group) ? group.replaceAll("\\d", "*") : group);
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
