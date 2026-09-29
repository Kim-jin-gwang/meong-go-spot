package com.meonggo.backend.auth.web;

import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.global.error.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.ObjectMapper;

/** 길이 헤더를 신뢰하지 않고 JSON 변환 전에 본문 크기를 제한한다. */
public class AuthRequestBodyFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 8192;
    private static final Set<String> PATHS =
            Set.of(
                    "/api/v1/auth/signup",
                    "/api/v1/auth/login",
                    "/api/v1/auth/tokens/refresh",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/phone-verifications",
                    "/api/v1/auth/phone-verifications/confirm",
                    "/api/v1/auth/account-recovery/phone-verifications",
                    "/api/v1/auth/account-recovery/phone-verifications/confirm",
                    "/api/v1/auth/account-recovery/password");
    private final ObjectMapper mapper;

    public AuthRequestBodyFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // requestURI의 percent escape 대신 컨테이너가 해석한 경로로 MVC와 같은 대상을 검사한다.
        String path = request.getServletPath();
        if (request.getPathInfo() != null) {
            path += request.getPathInfo();
        }
        if (path.isEmpty()) {
            path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        }
        return !"POST".equals(request.getMethod()) || !PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader("Content-Encoding") != null
                || request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        // 한 바이트를 더 읽어 Content-Length가 없는 전송의 상한 초과를 구분한다.
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(400);
        response.setContentType("application/json");
        mapper.writeValue(
                response.getOutputStream(), ApiResponse.error(CommonErrorCode.INVALID_INPUT));
    }

    private static class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return input.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("Authentication body uses synchronous reads");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
