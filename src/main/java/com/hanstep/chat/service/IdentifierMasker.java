package com.hanstep.chat.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 본문 저장 전 식별번호 마스킹(USER·ASSISTANT 둘 다).
 * 외국인등록번호 형식(6자리-7자리), 13~16자리 연속 숫자(하이픈 없는 등록번호 13자리, 카드·계좌번호 등).
 * 숫자만 * 로 바꾸고 하이픈·길이는 그대로 둔다. 앞뒤가 숫자인 더 긴 숫자열 일부는 건드리지 않는다.
 */
public final class IdentifierMasker {

    private static final Pattern REGISTRATION = Pattern.compile("(?<!\\d)\\d{6}-\\d{7}(?!\\d)");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{13,16}(?!\\d)");

    private IdentifierMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return replace(LONG_DIGITS, replace(REGISTRATION, text));
    }

    private static String replace(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, matcher.group().replaceAll("\\d", "*"));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
