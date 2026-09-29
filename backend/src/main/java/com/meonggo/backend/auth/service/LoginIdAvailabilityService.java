package com.meonggo.backend.auth.service;

import com.meonggo.backend.auth.dto.LoginIdAvailabilityResponse;
import com.meonggo.backend.auth.security.SignupInputPolicy;
import com.meonggo.backend.member.repository.MemberRepository;
import org.springframework.stereotype.Service;

@Service
public class LoginIdAvailabilityService {
    private final SignupInputPolicy inputs;
    private final MemberRepository members;

    public LoginIdAvailabilityService(SignupInputPolicy inputs, MemberRepository members) {
        this.inputs = inputs;
        this.members = members;
    }

    public LoginIdAvailabilityResponse check(String loginId) {
        String canonicalLoginId = inputs.canonicalLoginId(loginId);
        return new LoginIdAvailabilityResponse(!members.existsByLoginId(canonicalLoginId));
    }
}
