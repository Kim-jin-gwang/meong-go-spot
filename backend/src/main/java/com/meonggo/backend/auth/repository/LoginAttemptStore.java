package com.meonggo.backend.auth.repository;

/** 계정·IP 평문 대신 목적별 HMAC만 받는 원자적 로그인 제한 경계. */
public interface LoginAttemptStore {
    void check(String accountHash, String ipHash);

    void failure(String accountHash, String ipHash);

    void success(String accountHash);
}
