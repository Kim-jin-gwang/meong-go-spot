package com.meonggo.backend.global.security;

import com.meonggo.backend.auth.security.JwtTokenService;
import com.meonggo.backend.auth.service.SessionService;
import com.meonggo.backend.auth.web.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtTokenService tokens,
            SessionService sessions,
            SecurityErrorResponseWriter errors)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .addFilterBefore(
                        new JwtAuthenticationFilter(tokens, sessions, errors),
                        UsernamePasswordAuthenticationFilter.class)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(
                        requests ->
                                requests.requestMatchers(HttpMethod.GET, "/privacy")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.HEAD, "/privacy")
                                        .permitAll()
                                        .requestMatchers("/api/v1/ping", "/api/v1/ping/errors/**")
                                        .permitAll()
                                        // 앱 버전 안내 — 로그인 전에도 강제 업데이트를 알려야 한다
                                        .requestMatchers(HttpMethod.GET, "/api/v1/app/version")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/auth/login-ids/availability")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/tokens/refresh",
                                                "/api/v1/auth/signup",
                                                "/api/v1/auth/phone-verifications",
                                                "/api/v1/auth/phone-verifications/confirm",
                                                // 계정 찾기(A9) — 로그인할 수 없는 사람이 쓰는 API 다
                                                "/api/v1/auth/account-recovery/phone-verifications",
                                                "/api/v1/auth/account-recovery/phone-verifications/confirm",
                                                "/api/v1/auth/account-recovery/password")
                                        .permitAll()
                                        // 상세는 공개 조회다. 정확한 위치 등 로그인 전용 필드는
                                        // PostDetailService가 principal 유무로 가른다.
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/posts",
                                                "/api/v1/posts/{postId}",
                                                // 1.5 앱과의 서버 선배포 호환성. 회원별 넘김 제외는
                                                // 인증된 요청에만 AdoptionController가 적용한다.
                                                "/api/v1/adoptions",
                                                "/api/v1/photos/{photoId}",
                                                "/api/v1/data-sources/shelter-animals/daily-summary",
                                                "/api/v1/data-sources/shelter-animals/insights")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated());
        return http.build();
    }
}
