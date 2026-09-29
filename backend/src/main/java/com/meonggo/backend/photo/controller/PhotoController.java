package com.meonggo.backend.photo.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import com.meonggo.backend.photo.service.PhotoReadService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/photos")
public class PhotoController {
    private final PhotoReadService photos;

    public PhotoController(PhotoReadService photos) {
        this.photos = photos;
    }

    @GetMapping("/{photoId}")
    public void get(
            @PathVariable long photoId,
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletResponse response) {
        try (var content = photos.open(photoId, principal == null ? null : principal.memberId())) {
            response.setContentType("image/jpeg");
            response.setContentLengthLong(content.byteSize());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("ETag", "\"" + content.checksum() + "\"");
            content.writeTo(response.getOutputStream());
        } catch (BusinessException ex) {
            if (!response.isCommitted()) response.reset();
            throw ex;
        } catch (IOException ex) {
            if (!response.isCommitted()) response.reset();
            throw new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE);
        }
    }
}
