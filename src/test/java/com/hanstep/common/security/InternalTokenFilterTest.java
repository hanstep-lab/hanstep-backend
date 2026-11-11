package com.hanstep.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class InternalTokenFilterTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef"; // 32자

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 키와_같은_토큰이면_내부_호출자로_인증한다() throws Exception {
        assertThat(authenticate(new InternalTokenFilter(new InternalApiProperties(KEY)), KEY)).isTrue();
    }

    @Test
    void 토큰이_다르면_인증하지_않는다() throws Exception {
        assertThat(authenticate(new InternalTokenFilter(new InternalApiProperties(KEY)), KEY + "x")).isFalse();
        assertThat(authenticate(new InternalTokenFilter(new InternalApiProperties(KEY)), null)).isFalse();
    }

    @Test
    void 키가_비었거나_32자_미만이면_어떤_토큰도_거부한다() throws Exception {
        String shortKey = KEY.substring(1);
        assertThat(authenticate(new InternalTokenFilter(new InternalApiProperties(shortKey)), shortKey)).isFalse();
        assertThat(authenticate(new InternalTokenFilter(new InternalApiProperties("")), "")).isFalse();
        assertThat(authenticate(new InternalTokenFilter(new InternalApiProperties(null)), "")).isFalse();
    }

    private static boolean authenticate(InternalTokenFilter filter, String token) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/x");
        if (token != null) {
            request.addHeader(InternalTokenFilter.HEADER, token);
        }
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }
}
