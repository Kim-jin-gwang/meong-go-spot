package com.meonggo.backend.post.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.post.dto.UpdatePostResponse;
import com.meonggo.backend.post.service.PostMetadataService;
import com.meonggo.backend.post.web.PostJsonReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/posts")
public class PostMetadataController {
    private final PostMetadataService posts;
    private final PostJsonReader reader;

    public PostMetadataController(PostMetadataService posts, PostJsonReader reader) {
        this.posts = posts;
        this.reader = reader;
    }

    @PatchMapping(value = "/{postId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UpdatePostResponse> update(
            @PathVariable long postId,
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request) {
        return ApiResponse.success(
                "게시물을 수정했습니다.", posts.update(postId, principal.memberId(), reader.read(request)));
    }
}
