package com.hanstep.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    private static final String SECRET = base64(32, (byte) 7);
    private static final String OTHER_SECRET = base64(32, (byte) 9);

    private final JwtTokenProvider provider = new JwtTokenProvider(new JwtProperties(SECRET, 3600));

    @Test
    void 발급한_토큰에서_사용자_ID를_꺼낸다() {
        String token = provider.issue(1L);

        assertThat(provider.parseUserId(token)).contains(1L);
    }

    @Test
    void 다른_키로_서명한_토큰은_거부한다() {
        JwtTokenProvider other = new JwtTokenProvider(new JwtProperties(OTHER_SECRET, 3600));

        assertThat(provider.parseUserId(other.issue(1L))).isEmpty();
    }

    @Test
    void 만료된_토큰은_거부한다() {
        String expired = Jwts.builder()
                .issuer(JwtTokenProvider.ISSUER)
                .subject("1")
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(provider.parseUserId(expired)).isEmpty();
    }

    @Test
    void 발급자가_다르면_거부한다() {
        String foreign = Jwts.builder()
                .issuer("someone-else")
                .subject("1")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(provider.parseUserId(foreign)).isEmpty();
    }

    @Test
    void 형식이_깨진_토큰은_거부한다() {
        assertThat(provider.parseUserId("not-a-jwt")).isEmpty();
        assertThat(provider.parseUserId("")).isEmpty();
    }

    @Test
    void 키가_32바이트보다_짧으면_시작하지_않는다() {
        assertThatThrownBy(() -> new JwtTokenProvider(new JwtProperties(base64(16, (byte) 1), 3600)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키가_없으면_시작하지_않는다() {
        assertThatThrownBy(() -> new JwtTokenProvider(new JwtProperties(null, 3600)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String base64(int length, byte fill) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
