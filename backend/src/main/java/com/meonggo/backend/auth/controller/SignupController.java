package com.meonggo.backend.auth.controller;

import com.meonggo.backend.auth.dto.LoginIdAvailabilityResponse;
import com.meonggo.backend.auth.dto.PhoneConfirmationRequest;
import com.meonggo.backend.auth.dto.PhoneVerificationRequest;
import com.meonggo.backend.auth.dto.PhoneVerificationResponse;
import com.meonggo.backend.auth.dto.SignupRequest;
import com.meonggo.backend.auth.dto.SignupResponse;
import com.meonggo.backend.auth.service.LoginIdAvailabilityService;
import com.meonggo.backend.auth.service.PhoneVerificationService;
import com.meonggo.backend.auth.service.SignupService;
import com.meonggo.backend.auth.web.ClientIpResolver;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.member.exception.MemberErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class SignupController {
    private final SignupService signup;
    private final LoginIdAvailabilityService loginIds;
    private final PhoneVerificationService phones;
    private final ClientIpResolver clientIps;

    public SignupController(
            SignupService signup,
            LoginIdAvailabilityService loginIds,
            PhoneVerificationService phones,
            ClientIpResolver clientIps) {
        this.signup = signup;
        this.loginIds = loginIds;
        this.phones = phones;
        this.clientIps = clientIps;
    }

    @GetMapping("/login-ids/availability")
    public ResponseEntity<ApiResponse<LoginIdAvailabilityResponse>> checkLoginIdAvailability(
            @RequestParam("loginId") String loginId) {
        LoginIdAvailabilityResponse response = loginIds.check(loginId);
        String message =
                response.available()
                        ? "사용할 수 있는 아이디입니다."
                        : MemberErrorCode.LOGIN_ID_IN_USE.message();
        return ResponseEntity.ok(ApiResponse.success(message, response));
    }

    @PostMapping("/phone-verifications")
    public ResponseEntity<ApiResponse<Void>> requestCode(
            @Valid @RequestBody PhoneVerificationRequest request,
            HttpServletRequest servletRequest) {
        phones.requestCode(
                request.phoneNumber(),
                clientIps.resolve(servletRequest),
                request.privacyCollectionAgreed(),
                request.privacyCollectionPolicyVersion());
        return ResponseEntity.accepted().body(ApiResponse.success("인증 코드를 전송했습니다.", null));
    }

    @PostMapping("/phone-verifications/confirm")
    public ResponseEntity<ApiResponse<PhoneVerificationResponse>> confirm(
            @Valid @RequestBody PhoneConfirmationRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        "휴대전화 인증을 완료했습니다.",
                        phones.confirm(
                                request.phoneNumber(),
                                request.verificationCode(),
                                request.privacyCollectionAgreed(),
                                request.privacyCollectionPolicyVersion())));
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("회원가입에 성공했습니다.", signup.signup(request)));
    }
}
