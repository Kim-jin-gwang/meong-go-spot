package com.meonggo.backend.global.common.response;

/** 필드 검증 오류 한 건. 보안상 사용자가 입력한 rejectedValue는 포함하지 않는다. */
public record ValidationError(String field, String reason) {}
