package com.meonggo.backend.matching.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.matching.dto.MatchRequestResponse;
import com.meonggo.backend.matching.service.MatchRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/posts/{postId}/match-runs")
public class MatchRunController {
    private final MatchRequestService matches;

    public MatchRunController(MatchRequestService matches) {
        this.matches = matches;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MatchRequestResponse>> request(
            @PathVariable long postId, @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.accepted()
                .header("Cache-Control", "no-store")
                .body(
                        ApiResponse.success(
                                "유사도 분석을 접수했습니다.", matches.request(postId, principal.memberId())));
    }
}
