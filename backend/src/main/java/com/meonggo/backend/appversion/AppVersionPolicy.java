package com.meonggo.backend.appversion;

/**
 * 앱이 시작할 때 비교하는 버전 정책 — 플랫폼 하나(android)의 최신·최소 지원 {@code versionCode}와 스토어 링크.
 *
 * <p>값은 배포 설정({@code compose.prod.yml}, {@code APP_ANDROID_*})에서 오고 릴리즈마다 함께 올린다. {@code
 * minSupportedVersionCode} 는 서버 API 가 옛 앱과 호환되지 않게 바뀐 배포에서만 올린다 — 이 값 미만의 앱은 업데이트 전까지 들어오지 못한다. 0
 * 이면 어떤 안내도 뜨지 않는다(로컬·테스트 기본).
 */
public record AppVersionPolicy(
        String platform, int latestVersionCode, int minSupportedVersionCode, String storeUrl) {
    public static final String ANDROID = "android";
}
