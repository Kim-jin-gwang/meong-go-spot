package com.meonggo.backend.appversion;

import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.global.common.response.ApiResponse;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * V1 — 앱이 시작할 때 부르는 버전 안내. 인증 없음(로그인 전에도 강제 업데이트를 알려야 한다).
 *
 * <p>앱은 자기 {@code versionCode}를 {@code minSupportedVersionCode}·{@code latestVersionCode}와 비교해
 * 강제/권고 안내를 고른다. 판단 기준을 서버가 주는 이유: 서버 API 가 옛 앱과 호환되지 않게 바뀌었을 때(2026-09 전국 필터 400, 소개팅 401) 앱 배포를
 * 기다리지 않고 옛 앱을 막을 수 있어야 한다.
 */
@RestController
public class AppVersionController {
    private final AppVersionPolicy android;

    public AppVersionController(AppVersionPolicy android) {
        this.android = android;
    }

    @GetMapping("/api/v1/app/version")
    public ResponseEntity<ApiResponse<AppVersionResponse>> version(
            @RequestParam(name = "platform", defaultValue = AppVersionPolicy.ANDROID)
                    String platform) {
        if (!AppVersionPolicy.ANDROID.equals(platform.strip().toLowerCase(Locale.ROOT))) {
            throw new InputValidationException("platform", "지원하지 않는 플랫폼입니다.");
        }
        return ResponseEntity.ok(ApiResponse.success(AppVersionResponse.from(android)));
    }
}
