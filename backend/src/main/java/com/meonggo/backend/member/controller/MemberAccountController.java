package com.meonggo.backend.member.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.auth.web.ClientIpResolver;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.member.dto.MemberProfileResponse;
import com.meonggo.backend.member.dto.NicknameUpdateRequest;
import com.meonggo.backend.member.dto.PasswordChangeRequest;
import com.meonggo.backend.member.dto.WithdrawalRequest;
import com.meonggo.backend.member.service.MemberAccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 본인 계정 API — A5 탈퇴, A6 프로필, A7 닉네임 변경, A8 비밀번호 변경 (docs/api-spec.md §5). */
@RestController
@RequestMapping("/api/v1/members/me")
public class MemberAccountController {
    private final MemberAccountService accounts;
    private final ClientIpResolver clientIps;

    public MemberAccountController(MemberAccountService accounts, ClientIpResolver clientIps) {
        this.accounts = accounts;
        this.clientIps = clientIps;
    }

    @GetMapping
    public ApiResponse<MemberProfileResponse> profile(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.success(accounts.profile(principal));
    }

    @PatchMapping
    public ApiResponse<MemberProfileResponse> changeNickname(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody NicknameUpdateRequest request) {
        return ApiResponse.success("닉네임을 변경했습니다.", accounts.changeNickname(principal, request));
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody PasswordChangeRequest request,
            HttpServletRequest servletRequest) {
        accounts.changePassword(principal, request, clientIps.resolve(servletRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/withdrawal")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody WithdrawalRequest request,
            HttpServletRequest servletRequest) {
        accounts.withdraw(principal, request, clientIps.resolve(servletRequest));
        return ResponseEntity.noContent().build();
    }
}
