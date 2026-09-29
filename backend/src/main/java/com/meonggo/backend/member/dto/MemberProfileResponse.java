package com.meonggo.backend.member.dto;

/** 본인 프로필 — 전화번호·로그인 ID 는 응답하지 않는다 (docs/api-spec.md A6). */
public record MemberProfileResponse(long memberId, String nickname) {}
