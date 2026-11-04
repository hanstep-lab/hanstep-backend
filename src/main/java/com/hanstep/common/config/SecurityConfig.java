package com.hanstep.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 로그인 없이 열어 둘 주소
    private static final String[] PUBLIC_URLS = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/api/v1/health"
    };

    private final SecurityErrorHandler securityErrorHandler;

    public SecurityConfig(SecurityErrorHandler securityErrorHandler) {
        this.securityErrorHandler = securityErrorHandler;
    }

    @Bean
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
                        .anyRequest().authenticated());

        return http.build();
    }
}