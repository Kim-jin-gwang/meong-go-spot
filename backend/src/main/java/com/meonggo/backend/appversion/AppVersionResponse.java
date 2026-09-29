package com.meonggo.backend.appversion;

/** V1 응답. 값이 0 이면 앱은 안내를 띄우지 않는다. */
public record AppVersionResponse(
        String platform, int latestVersionCode, int minSupportedVersionCode, String storeUrl) {
    static AppVersionResponse from(AppVersionPolicy policy) {
        return new AppVersionResponse(
                policy.platform(),
                policy.latestVersionCode(),
                policy.minSupportedVersionCode(),
                policy.storeUrl());
    }
}
