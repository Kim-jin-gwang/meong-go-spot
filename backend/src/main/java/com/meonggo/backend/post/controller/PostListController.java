package com.meonggo.backend.post.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.post.dto.PostListResponse;
import com.meonggo.backend.post.dto.PostListResponse.OwnSummary;
import com.meonggo.backend.post.dto.PostListResponse.PublicSummary;
import com.meonggo.backend.post.query.PostListInputPolicy;
import com.meonggo.backend.post.service.PostListService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PostListController {
    private final PostListInputPolicy inputs;
    private final PostListService posts;

    public PostListController(PostListInputPolicy inputs, PostListService posts) {
        this.inputs = inputs;
        this.posts = posts;
    }

    @GetMapping("/api/v1/posts")
    public ResponseEntity<ApiResponse<PostListResponse<PublicSummary>>> publicPosts(
            @RequestParam MultiValueMap<String, String> params) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        ApiResponse.success(
                                "게시물 목록을 조회했습니다.", posts.publicPosts(inputs.publicQuery(params))));
    }

    @GetMapping("/api/v1/members/me/posts")
    public ResponseEntity<ApiResponse<PostListResponse<OwnSummary>>> ownPosts(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam MultiValueMap<String, String> params) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        ApiResponse.success(
                                "내 게시물을 조회했습니다.",
                                posts.ownPosts(inputs.ownQuery(params), principal.memberId())));
    }
}
