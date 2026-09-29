package com.meonggo.backend.auth.controller;

import com.meonggo.backend.auth.dto.AccountRecoveryConfirmationRequest;
import com.meonggo.backend.auth.dto.AccountRecoveryConfirmationResponse;
import com.meonggo.backend.auth.dto.AccountRecoveryPhoneRequest;
import com.meonggo.backend.auth.dto.PasswordResetRequest;
import com.meonggo.backend.auth.service.AccountRecoveryService;
import com.meonggo.backend.auth.web.ClientIpResolver;
import com.meonggo.backend.global.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A9 계정 찾기 — 휴대전화 인증으로 아이디 확인과 비밀번호 재설정. 전부 비로그인 요청이다. */
@RestController
@RequestMapping("/api/v1/auth/account-recovery")
public class AccountRecoveryController {
    private final AccountRecoveryService recovery;
    private final ClientIpResolver clientIps;

    public AccountRecoveryController(AccountRecoveryService recovery, ClientIpResolver clientIps) {
        this.recovery = recovery;
        this.clientIps = clientIps;
    }

    @PostMapping("/phone-verifications")
    public ResponseEntity<ApiResponse<Void>> requestCode(
            @Valid @RequestBody AccountRecoveryPhoneRequest request,
            HttpServletRequest servletRequest) {
        recovery.requestCode(request.phoneNumber(), clientIps.resolve(servletRequest));
        // 가입된 번호인지 응답으로 알려 주지 않는다 — 문구도 같다.
        return ResponseEntity.accepted().body(ApiResponse.success("인증 코드를 전송했습니다.", null));
    }

    @PostMapping("/phone-verifications/confirm")
    public ResponseEntity<ApiResponse<AccountRecoveryConfirmationResponse>> confirm(
            @Valid @RequestBody AccountRecoveryConfirmationRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        "휴대전화 인증을 완료했습니다.",
                        recovery.confirm(request.phoneNumber(), request.verificationCode())));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        recovery.resetPassword(request);
        return ResponseEntity.noContent().build();
    }
}
