package com.meonggo.backend.post.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.post.dto.CreatePostResponse;
import com.meonggo.backend.post.entity.CaseType;
import com.meonggo.backend.post.service.PostCreationService;
import com.meonggo.backend.post.service.PostInputPolicy;
import com.meonggo.backend.post.web.PostMultipartReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/posts")
public class PostController {
    private final PostMultipartReader multipart;
    private final PostInputPolicy inputs;
    private final PostCreationService posts;

    public PostController(
            PostMultipartReader multipart, PostInputPolicy inputs, PostCreationService posts) {
        this.multipart = multipart;
        this.inputs = inputs;
        this.posts = posts;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CreatePostResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        var input = multipart.read(request);
        var response =
                posts.create(
                        principal.memberId(), inputs.validate(input.metadata()), input.photos());
        String message =
                response.type() == CaseType.LOST ? "찾고 있어요 게시물을 등록했습니다." : "보호하고 있어요 게시물을 등록했습니다.";
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(message, response));
    }
}
