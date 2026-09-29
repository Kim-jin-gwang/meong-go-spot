package com.meonggo.backend.auth.controller;

import com.meonggo.backend.auth.dto.LoginRequest;
import com.meonggo.backend.auth.dto.LoginResponse;
import com.meonggo.backend.auth.dto.RefreshRequest;
import com.meonggo.backend.auth.dto.TokenResponse;
import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import com.meonggo.backend.auth.web.ClientIpResolver;
import com.meonggo.backend.global.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {
    private final LoginService login;
    private final SessionService sessions;
    private final ClientIpResolver clientIps;

    public SessionController(
            LoginService login, SessionService sessions, ClientIpResolver clientIps) {
        this.login = login;
        this.sessions = sessions;
        this.clientIps = clientIps;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return uncached()
                .body(
                        ApiResponse.success(
                                "로그인에 성공했습니다.",
                                login.login(request, clientIps.resolve(servletRequest))));
    }

    @PostMapping("/tokens/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@RequestBody RefreshRequest request) {
        return uncached()
                .body(ApiResponse.success("토큰을 갱신했습니다.", sessions.refresh(request.refreshToken())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestBody RefreshRequest request) {
        sessions.logout(principal, request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity.BodyBuilder uncached() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache");
    }
}
