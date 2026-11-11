package com.hanstep.common.security;

/** 내부 API 테스트 공통 키. 같은 properties 를 쓰면 스프링 컨텍스트를 같이 쓴다. */
public final class InternalApiTestSupport {

    public static final String API_KEY = "test-internal-api-key-0123456789abcdef";
    public static final String KEY_PROPERTY = "hanstep.internal.api-key=" + API_KEY;

    private InternalApiTestSupport() {
    }
}
