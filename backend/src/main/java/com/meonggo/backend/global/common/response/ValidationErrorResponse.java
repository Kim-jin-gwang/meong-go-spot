package com.meonggo.backend.global.common.response;

import java.util.List;

/** 검증 오류 응답의 data 페이로드 — {@code data.fieldErrors} 형태로 직렬화된다. */
public record ValidationErrorResponse(List<ValidationError> fieldErrors) {}
