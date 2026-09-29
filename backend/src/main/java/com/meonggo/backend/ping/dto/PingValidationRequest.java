package com.meonggo.backend.ping.dto;

import jakarta.validation.constraints.NotBlank;

public record PingValidationRequest(@NotBlank(message = "메시지는 필수입니다.") String message) {}
