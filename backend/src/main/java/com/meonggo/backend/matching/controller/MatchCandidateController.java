package com.meonggo.backend.matching.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.matching.dto.MatchCandidatesResponse;
import com.meonggo.backend.matching.service.MatchCandidateService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/posts")
public class MatchCandidateController {
    private final MatchCandidateService matches;

    public MatchCandidateController(MatchCandidateService matches) {
        this.matches = matches;
    }

    @GetMapping("/{postId}/candidates")
    public ApiResponse<MatchCandidatesResponse> candidates(
            @PathVariable long postId, @AuthenticationPrincipal AuthPrincipal principal) {
        var response = matches.candidates(postId, principal.memberId());
        return ApiResponse.success(
                response.recommendedPollAfterMs() == null ? "유사 후보를 조회했습니다." : "매칭을 처리하고 있습니다.",
                response);
    }
}
