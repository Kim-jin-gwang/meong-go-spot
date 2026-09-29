package com.meonggo.backend.post.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.post.dto.PostDetailResponse;
import com.meonggo.backend.post.service.PostDetailService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/posts")
public class PostDetailController {
    private final PostDetailService posts;

    public PostDetailController(PostDetailService posts) {
        this.posts = posts;
    }

    @GetMapping("/{postId}")
    public ApiResponse<PostDetailResponse> detail(
            @PathVariable long postId, @AuthenticationPrincipal AuthPrincipal principal) {
        Long viewerId = principal == null ? null : principal.memberId();
        return ApiResponse.success("게시물을 조회했습니다.", posts.detail(postId, viewerId));
    }
}
