package com.meonggo.backend.photo.web;

import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/** multipart 파싱부터 정규화 결과의 저장까지 실행 수를 제한해 메모리·임시 디스크 대기를 막는다. */
@Component
@Order(1)
public class PhotoUploadCapacityFilter extends OncePerRequestFilter {
    private final Semaphore permits;
    private final JsonMapper mapper;

    public PhotoUploadCapacityFilter(
            JsonMapper mapper, @Value("${PHOTO_UPLOAD_MAX_CONCURRENCY:1}") int concurrency) {
        if (concurrency < 1 || concurrency > 4)
            throw new IllegalStateException("사진 동시 처리 수는 1~4여야 합니다.");
        permits = new Semaphore(concurrency);
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
        if (!permits.tryAcquire()) {
            response.setStatus(503);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json");
            mapper.writeValue(
                    response.getOutputStream(), ApiResponse.error(PhotoErrorCode.UPLOAD_BUSY));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
