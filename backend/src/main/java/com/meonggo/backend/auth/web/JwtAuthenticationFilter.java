package com.meonggo.backend.auth.web;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.security.JwtTokenService;
import com.meonggo.backend.auth.service.SessionService;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.global.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/** 서명 검증과 DB 세션 검사를 통과한 회원·세션 ID만 SecurityContext에 전달한다. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenService tokens;
    private final SessionService sessions;
    private final SecurityErrorResponseWriter errors;

    public JwtAuthenticationFilter(
            JwtTokenService tokens, SessionService sessions, SecurityErrorResponseWriter errors) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (!headers.isEmpty()) {
            try {
                if (headers.size() != 1)
                    throw new BusinessException(AuthErrorCode.AUTHENTICATION_REQUIRED);
                String header = headers.getFirst();
                if (header.length() > 8192 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    throw new BusinessException(AuthErrorCode.AUTHENTICATION_REQUIRED);
                }
                var principal = tokens.verify(header.substring(7));
                if (!isLogout(request)) sessions.validate(principal);
                var authentication =
                        UsernamePasswordAuthenticationToken.authenticated(
                                principal, null, List.of());
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            } catch (BusinessException ex) {
                SecurityContextHolder.clearContext();
                errors.write(response, ex.errorCode());
                return;
            } catch (RuntimeException ex) {
                SecurityContextHolder.clearContext();
                // DB 장애의 원문에는 조회 조건이나 자격 증명이 포함될 수 있다.
                logger.error("Authentication session validation failed");
                errors.write(response, CommonErrorCode.INTERNAL_SERVER_ERROR);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isLogout(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && "/api/v1/auth/logout"
                        .equals(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }
}
