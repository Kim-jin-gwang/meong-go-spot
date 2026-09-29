package com.meonggo.backend.photo.web;

import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.global.error.ErrorCode;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/** 헤더로 확인 가능한 오류는 multipart 임시 파일 생성 전에 거부한다. */
@Component
@Order(0)
public class PhotoRequestFilter extends OncePerRequestFilter {
    private final JsonMapper mapper;

    public PhotoRequestFilter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (request.getPathInfo() != null) path += request.getPathInfo();
        if (path.isEmpty()) path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !("POST".equals(request.getMethod()) && path.equals("/api/v1/posts"))
                && !("PUT".equals(request.getMethod())
                        && path.matches("/api/v1/posts/[^/]+/photos"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ErrorCode error =
                request.getHeader("Content-Encoding") != null
                        ? CommonErrorCode.INVALID_INPUT
                        : request.getContentLengthLong() > 52428800
                                ? PhotoErrorCode.TOO_LARGE
                                : null;
        if (error == null) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(error.status().value());
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(error));
    }
}
