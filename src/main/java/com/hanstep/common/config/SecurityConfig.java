package com.hanstep.common.config;

import com.hanstep.common.security.InternalApiProperties;
import com.hanstep.common.security.InternalTokenFilter;
import com.hanstep.common.security.JwtAuthenticationFilter;
import com.hanstep.common.security.JwtTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 로그인 없이 열어 둘 주소
    private static final String[] PUBLIC_URLS = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/api/v1/health",
            "/api/v1/auth/entry",
            "/error"
    };

    private final SecurityErrorHandler securityErrorHandler;
    private final JwtTokenProvider jwtTokenProvider;
    private final InternalApiProperties internalApiProperties;

    public SecurityConfig(SecurityErrorHandler securityErrorHandler, JwtTokenProvider jwtTokenProvider,
                          InternalApiProperties internalApiProperties) {
        this.securityErrorHandler = securityErrorHandler;
        this.jwtTokenProvider = jwtTokenProvider;
        this.internalApiProperties = internalApiProperties;
    }

    // AI → Spring 내부 API. JWT 대신 X-Internal-Token 만 본다.
    @Bean
    @Order(1)
    public SecurityFilterChain internalSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(securityErrorHandler))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(InternalTokenFilter.ROLE))
                .addFilterBefore(new InternalTokenFilter(internalApiProperties),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(securityErrorHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_URLS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
