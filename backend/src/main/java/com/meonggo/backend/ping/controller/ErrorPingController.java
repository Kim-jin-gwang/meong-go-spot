package com.meonggo.backend.ping.controller;

import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.ping.dto.PingResponse;
import com.meonggo.backend.ping.dto.PingValidationRequest;
import com.meonggo.backend.ping.service.ErrorPingService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 오류별 공통 응답을 확인하는 참고 엔드포인트 (backend/docs/backend-ping-guide.md). 의도적으로 오류와 500 로그를 만들기 때문에 dev
 * 프로필에서만 등록된다.
 */
@RestController
@RequestMapping("/api/v1/ping/errors")
@Profile("dev")
public class ErrorPingController {

    private final ErrorPingService errorPingService;

    public ErrorPingController(ErrorPingService errorPingService) {
        this.errorPingService = errorPingService;
    }

    @GetMapping("/business")
    public ResponseEntity<ApiResponse<Void>> business() {
        errorPingService.throwBusinessError();
        return null; // 도달하지 않는다 — BusinessException이 항상 발생
    }

    @PostMapping("/validation")
    public ResponseEntity<ApiResponse<PingResponse>> validation(
            @Valid @RequestBody PingValidationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(PingResponse.pong()));
    }

    @GetMapping("/unexpected")
    public ResponseEntity<ApiResponse<Void>> unexpected() {
        errorPingService.throwUnexpectedError();
        return null; // 도달하지 않는다 — IllegalStateException이 항상 발생
    }

    @GetMapping("/forbidden")
    @PreAuthorize("denyAll")
    public ResponseEntity<ApiResponse<Void>> forbidden() {
        return ResponseEntity.noContent().build();
    }
}
