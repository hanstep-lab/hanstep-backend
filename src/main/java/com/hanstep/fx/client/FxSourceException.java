package com.hanstep.fx.client;

import java.util.regex.Pattern;

/**
 * 원천 호출 실패(연결·타임아웃·오류 상태·형식 오류·원천 오류 코드). reason 은 로그에 그대로 쓰므로 인증키를 가린 문구만 담고,
 * 원래 예외(메시지에 요청 URL·인증키가 있음)는 붙이지 않는다.
 * fatal 이면 같은 원천을 더 불러도 소용없다(인증키 오류·일일 한도 초과). 백필은 거기서 멈춘다.
 */
public class FxSourceException extends RuntimeException {

    private static final Pattern AUTH_KEY = Pattern.compile("(?i)(authkey=)[^&\s\"'<>]*");

    private final boolean fatal;

    public FxSourceException(String reason) {
        this(reason, false);
    }

    public FxSourceException(String reason, boolean fatal) {
        super(mask(reason), null, false, false);
        this.fatal = fatal;
    }

    public String reason() {
        return getMessage();
    }

    public boolean isFatal() {
        return fatal;
    }

    /** authkey= 뒤의 값을 **** 로 바꾼다. 예외 메시지에 요청 URL 이 그대로 들어 있어서 로그에 쓰기 전에 거친다. */
    public static String mask(String text) {
        return text == null ? null : AUTH_KEY.matcher(text).replaceAll("$1****");
    }
}
